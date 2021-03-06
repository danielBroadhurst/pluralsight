package com.pluralsight.reactive.translation.service;

import com.pluralsight.reactive.model.domain.*;
import com.pluralsight.reactive.translation.constant.SubmissionStatus;
import com.pluralsight.reactive.translation.exception.InternalFailureException;
import com.pluralsight.reactive.translation.exception.PreconditionFailedException;
import com.pluralsight.reactive.translation.exception.ResourceNotFoundException;
import com.pluralsight.reactive.translation.exception.UpstreamDependencyException;
import com.pluralsight.reactive.translation.mapper.SubmissionMapper;
import com.pluralsight.reactive.translation.model.domain.Submission;
import com.pluralsight.reactive.translation.model.dto.SubmissionDto;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Service
@Profile(value = "m05")
public class SimpleSubmissionServiceM05 implements SubmissionService {

    private final JdbcTemplate readJdbcTemplate;
    private final JdbcTemplate writeJdbcTemplate;
    private final KafkaProducer<String, DocumentSubmittedEvent> documentSubmittedEventKafkaProducer;
    private final KafkaProducer<String, SubmissionCancelledEvent> submissionCancelledEventKafkaProducer;
    private final SubmissionMapper submissionMapper;
    private final TranslationService translationService;

    @Autowired
    public SimpleSubmissionServiceM05(@Qualifier(value = "ReadJdbcTemplate") final JdbcTemplate readJdbcTemplate,
                                      @Qualifier(value = "WriteJdbcTemplate") final JdbcTemplate writeJdbcTemplate,
                                      final KafkaProducer<String, DocumentSubmittedEvent> documentSubmittedEventKafkaProducer,
                                      final KafkaProducer<String, SubmissionCancelledEvent> submissionCancelledEventKafkaProducer,
                                      final SubmissionMapper submissionMapper,
                                      final TranslationService translationService) {
        this.readJdbcTemplate = readJdbcTemplate;
        this.writeJdbcTemplate = writeJdbcTemplate;
        this.documentSubmittedEventKafkaProducer = documentSubmittedEventKafkaProducer;
        this.submissionCancelledEventKafkaProducer = submissionCancelledEventKafkaProducer;
        this.submissionMapper = submissionMapper;
        this.translationService = translationService;
    }

    @Override
    @Transactional
    public void startTransaction(final Authentication authentication,
                                 final String name,
                                 final String eTag,
                                 final String source,
                                 final String target,
                                 final long completionDate) {
        try {
            final String update =
                    "INSERT INTO submission (sid, principal, name, e_tag, source, target, completion_date, status, created_at, updated_at, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";

            final Timestamp now = Timestamp.from(Instant.now());
            final String sid = getSubmissionCorrelationId(authentication, name, eTag, target);

            writeJdbcTemplate.update(update,
                                     sid,
                                     authentication.getName(),
                                     name,
                                     eTag,
                                     source,
                                     target,
                                     Timestamp.from(Instant.ofEpochMilli(completionDate)),
                                     SubmissionStatus.PENDING_LOCK,
                                     now,
                                     now,
                                     true);

            final Future<RecordMetadata> future =
                    documentSubmittedEventKafkaProducer.send(new ProducerRecord<>("document-submitted",
                                                                                  DocumentSubmittedEvent.newBuilder()
                                                                                                        .setPrincipal(authentication.getName())
                                                                                                        .setName(name)
                                                                                                        .setETag(eTag)
                                                                                                        .setCreatedAt(now.getTime())
                                                                                                        .setId(sid)
                                                                                                        .build()));

            future.get();
        } catch (final DuplicateKeyException e) {
            throw new PreconditionFailedException("A submission already exists for this document.");
        } catch (final DataAccessException e) {
            throw new UpstreamDependencyException("Unable to communicate successfully with database.", e);
        } catch (final InterruptedException | ExecutionException e) {
            throw new InternalFailureException("Unable to communicate with message bus.", e);
        }
    }

    @Override
    @Transactional
    public void startRollback(final Authentication authentication, final String id) {
        final Submission submission = getSubmissionInternal(id);

        if (!authentication.getName().equals(submission.getPrincipal())) {
            throw new ResourceNotFoundException("The provided resource does not exist.");
        }

        if (submission.getStatus().equals(SubmissionStatus.CANCELLED) ||
            submission.getStatus().equals(SubmissionStatus.PENDING_CANCELLATION)) {
            return;
        }

        if (!submission.getStatus().equals(SubmissionStatus.PENDING_ACCEPTANCE)) {
            throw new PreconditionFailedException("The necessary precondition failed.");
        }

        startRollback(submission);
    }

    private void startRollback(final Submission submission) {
        final String update =
                "UPDATE submission SET status = ?, details = ?, updated_at = ? WHERE sid = ? AND active IS NOT NULL AND updated_at = ?;";
        final Timestamp now = Timestamp.from(Instant.now());

        try {
            writeJdbcTemplate.update(update,
                                     SubmissionStatus.PENDING_CANCELLATION,
                                     "Cancelled at user request.",
                                     now,
                                     submission.getId(),
                                     submission.getUpdatedAt());

            final Future<RecordMetadata> future =
                    submissionCancelledEventKafkaProducer.send(new ProducerRecord<>("submission-cancelled",
                                                                                    SubmissionCancelledEvent.newBuilder()
                                                                                                            .setPrincipal(submission.getPrincipal())
                                                                                                            .setName(submission.getName())
                                                                                                            .setETag(submission.getETag())
                                                                                                            .setCreatedAt(now.getTime())
                                                                                                            .setId(submission.getId())
                                                                                                            .build()));

            future.get();
        } catch (final DataAccessException e) {
            throw new UpstreamDependencyException("Unable to communicate successfully with database.", e);
        } catch (final InterruptedException | ExecutionException e) {
            throw new InternalFailureException("Unable to communicate with message bus.", e);
        }
    }

    @Override
    public void handleDocumentLockedEvent(final DocumentLockedEvent documentLockedEvent) {
        final Submission submission =
                getSubmissionInternal(documentLockedEvent.getId());

        if (!StringUtils.isEmpty(documentLockedEvent.getErrorCode())) {
            commitRollback(documentLockedEvent.getErrorMessage(), submission);
        } else {
            final String update =
                    "UPDATE submission SET status = ?, updated_at = ? WHERE sid = ? AND active IS NOT NULL AND updated_at = ?;";
            final Timestamp now = Timestamp.from(Instant.now());

            try {
                writeJdbcTemplate.update(update,
                                         SubmissionStatus.PENDING_ACCEPTANCE,
                                         now,
                                         submission.getId(),
                                         submission.getUpdatedAt());
            } catch (final DataAccessException e) {
                throw new UpstreamDependencyException("Unable to communicate successfully with database.", e);
            }

            translationService.notifyTranslators(submissionMapper.asDto(submission));
        }
    }

    @Override
    public void handleDocumentUnlockedEvent(final DocumentUnlockedEvent documentUnlockedEvent) {
        final Submission submission =
                getSubmissionInternal(documentUnlockedEvent.getId());

        if (submission.getStatus().equals(SubmissionStatus.PENDING_CANCELLATION) &&
            !StringUtils.isEmpty(documentUnlockedEvent.getErrorCode())) {
            startRollback(submission);
        } else if (submission.getStatus().equals(SubmissionStatus.PENDING_CANCELLATION)) {
            commitRollback(documentUnlockedEvent.getErrorMessage(), submission);
        }
    }

    @Override
    public SubmissionDto getSubmission(final Authentication authentication, final String id) {
        final Submission submission = getSubmissionInternal(id);

        if (!authentication.getName().equals(submission.getPrincipal())) {
            throw new ResourceNotFoundException("The provided resource does not exist.");
        }

        return submissionMapper.asDto(submission);
    }

    @Override
    public Collection<SubmissionDto> listSubmissions(final Authentication authentication) {
        final String query = "SELECT * FROM submission WHERE principal = ? AND active IS NOT NULL;";

        try {
            final Collection<Submission> submissions =
                    readJdbcTemplate.query(query,
                                           new Object[]{authentication.getName()},
                                           this::submissionRowMapper);

            return submissionMapper.asDto(submissions);
        } catch (final DataAccessException e) {
            throw new UpstreamDependencyException("Unable to communicate with database.", e);
        }
    }

    @Override
    public Collection<Submission> listPendingLockSubmissions(final String start,
                                                             final String end,
                                                             final long threshold,
                                                             final String after,
                                                             final int limit) {
        final String query =
                "SELECT * FROM submission " +
                "WHERE name BETWEEN ? AND ? " +
                "AND active IS NOT NULL " +
                "AND NOW() > DATEADD('MINUTE', ?, updated_at) " +
                "AND status = ? " +
                "ORDER BY name ASC " +
                "LIMIT ?;";

        try {
            return readJdbcTemplate.query(query,
                                          new Object[]{after != null ? after : start,
                                                       end,
                                                       threshold,
                                                       SubmissionStatus.PENDING_LOCK,
                                                       limit},
                                          this::submissionRowMapper);
        } catch (final DataAccessException e) {
            throw new UpstreamDependencyException("Unable to communicate with database.", e);
        }
    }

    @Override
    public Collection<Submission> listPendingAcceptanceSubmissions(final String start,
                                                                   final String end,
                                                                   final long threshold,
                                                                   final String after,
                                                                   final int limit) {
        final String query =
                "SELECT * FROM submission " +
                "WHERE name BETWEEN ? AND ? " +
                "AND active IS NOT NULL " +
                "AND NOW() > DATEADD('MINUTE', ?, updated_at) " +
                "AND status = ? " +
                "ORDER BY name ASC " +
                "LIMIT ?;";

        try {
            return readJdbcTemplate.query(query,
                                          new Object[]{after != null ? after : start,
                                                       end,
                                                       threshold,
                                                       SubmissionStatus.PENDING_ACCEPTANCE,
                                                       limit},
                                          this::submissionRowMapper);
        } catch (final DataAccessException e) {
            throw new UpstreamDependencyException("Unable to communicate with database.", e);
        }
    }

    @Override
    public void startRollback(final String id) {
        final Submission submission = getSubmissionInternal(id);

        if (submission.getStatus().equals(SubmissionStatus.CANCELLED) ||
            submission.getStatus().equals(SubmissionStatus.PENDING_CANCELLATION)) {
            return;
        }

        if (!submission.getStatus().equals(SubmissionStatus.PENDING_ACCEPTANCE)) {
            throw new PreconditionFailedException("The necessary precondition failed.");
        }

        startRollback(submission);
    }

    @Override
    @Transactional
    public void acceptSubmission(final Authentication authentication, final String id) {
        final Submission submission = getSubmissionInternal(id);

        commitTransaction(submission);

        translationService.assignTranslator(authentication, submissionMapper.asDto(submission));
    }

    private Submission getSubmissionInternal(final String id) {
        final String query = "SELECT * FROM submission WHERE sid = ? AND active IS NOT NULL;";

        try {
            final Submission submission =
                    readJdbcTemplate.queryForObject(query,
                                                    new Object[]{id},
                                                    this::submissionRowMapper);

            if (submission == null) {
                throw new ResourceNotFoundException("The provided resource does not exist.");
            }

            return submission;
        } catch (final IncorrectResultSizeDataAccessException e) {
            throw new ResourceNotFoundException("The provided resource does not exist.");
        } catch (final DataAccessException e) {
            throw new UpstreamDependencyException("Unable to communicate with database.", e);
        }
    }

    private void commitRollback(final String details, final Submission submission) {
        final String update =
                "UPDATE submission SET status = ?, details = ?, updated_at = ?, active = NULL WHERE sid = ? AND active IS NOT NULL;";
        final Timestamp now = Timestamp.from(Instant.now());

        try {
            writeJdbcTemplate.update(update,
                                     SubmissionStatus.CANCELLED,
                                     details,
                                     now,
                                     submission.getId());
        } catch (final DataAccessException e) {
            throw new UpstreamDependencyException("Unable to communicate successfully with database.", e);
        }
    }

    private void commitTransaction(final Submission submission) {
        final String update =
                "UPDATE submission SET status = ?, details = ?, updated_at = ? WHERE sid = ? AND ACTIVE IS NOT NULL;";

        final Timestamp now = Timestamp.from(Instant.now());

        try {
            writeJdbcTemplate.update(update,
                                     SubmissionStatus.ACCEPTED,
                                     "Translation request accepted.",
                                     now,
                                     submission.getId());
        } catch (final DataAccessException e) {
            throw new UpstreamDependencyException("Unable to communicate successfully with database.", e);
        }
    }

    private Submission submissionRowMapper(final ResultSet resultSet, final int ignored) throws SQLException {
        return Submission.builder()
                         .id(resultSet.getString("sid"))
                         .principal(resultSet.getString("principal"))
                         .name(resultSet.getString("name"))
                         .eTag(resultSet.getString("e_tag"))
                         .status(resultSet.getString("status"))
                         .details(resultSet.getString("details"))
                         .source(resultSet.getString("source"))
                         .target(resultSet.getString("target"))
                         .completionDate(resultSet.getTimestamp("completion_date"))
                         .createdAt(resultSet.getTimestamp("created_at"))
                         .updatedAt(resultSet.getTimestamp("updated_at"))
                         .build();
    }

    private String getSubmissionCorrelationId(final Authentication authentication,
                                              final String name,
                                              final String eTag,
                                              final String target) {
        return DigestUtils.sha256Hex(String.format("%s%s%s%s", authentication.getName(), name, eTag, target));
    }
}
