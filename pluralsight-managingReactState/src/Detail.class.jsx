import React from "react";
import { useParams, useNavigate } from "react-router-dom";
import { CartContext } from "./cartContext";
import PageNotFound from "./PageNotFound";
import { Fetch } from "./services/useFetch";
import Spinner from "./Spinner";

export default function DetailWrapper() {
  const { id } = useParams();
  return <Detail id={id} navigate={useNavigate()}></Detail>;
}

class Detail extends React.Component {
  state = {
    sku: "",
  };

  //static contextType = CartContext;

  render() {
    const { id, navigate } = this.props;
    const { sku } = this.state;

    return (
      <CartContext.Consumer>
        {({ dispatch }) => {
          <Fetch url={`products/${id}`}>
            {(product, loading, error) => {
              if (loading) return <Spinner />;
              if (!product) return <PageNotFound />;
              if (error) throw error;

              return (
                <div id="detail">
                  <h1>{product.name}</h1>
                  <p>{product.description}</p>
                  <p id="price">${product.price}</p>

                  <select
                    id="size"
                    value={sku}
                    onChange={(e) => this.setSku({ sku: e.target.value })}
                  >
                    <option value="">What size?</option>
                    {product.skus.map((s) => (
                      <option key={s.sku} value={s.sku}>
                        {s.size}
                      </option>
                    ))}
                  </select>

                  <p>
                    <button
                      disabled={!sku}
                      className="btn btn-primary"
                      onClick={() => {
                        //this.context.dispatch({ type: "add", id, sku });
                        dispatch({ type: "add", id, sku });
                        navigate("/cart");
                      }}
                    >
                      Add to Cart
                    </button>
                  </p>
                  <img
                    src={`/images/${product.image}`}
                    alt={product.category}
                  />
                </div>
              );
            }}
          </Fetch>;
        }}
      </CartContext.Consumer>
    );
  }
}
