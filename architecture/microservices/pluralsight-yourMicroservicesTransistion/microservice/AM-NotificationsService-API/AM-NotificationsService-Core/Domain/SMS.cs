﻿using System;
using System.Collections.Generic;
using System.Text;

namespace AM_NotificationsService_Core
{
    public class SMS
    {
        public int Id { get; set; }
        public String SendTo { get; set; }
        public String Sender { get; set; }
        public String Message { get; set; }
        public DateTime SentDateTime { get; set; }
        public DateTime DeliveredDateTime { get; set; }
    }
}
