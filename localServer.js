const express = require("express");
const app = express();

app.use(express.json());

/**
 * Global invalid URL handler
 * If request does not match any known route, return 400 instead of 404
 * This allows the self-healing engine to learn URL rewrites.
 */
app.use((req, res, next) => {
  const validRoutes = [
    "/v1/orders",
    "/v2/createOrder"
  ];

  if (!validRoutes.includes(req.path)) {
    return res.status(400).json({
      error: `Invalid request url: ${req.path}. Use /v2/createOrder`,
      received: req.path
    });
  }

  next();
});

/**
 * Deprecated v1 endpoint
 * Always fails with mapping instructions
 */
app.post("/v1/orders", (req, res) => {
  const { customerName, customerAge } = req.body;

  return res.status(400).json({
    error: "Request url is deprecated. Use /v2/createOrder with fields { name, age }",
    received: { customerName, customerAge }
  });
});

/**
 * Correct v2 endpoint
 */
app.post("/v2/createOrder", (req, res) => {
  const { name, age } = req.body;

  if (!name) {
    return res.status(400).json({ error: "Missing or invalid field: name" });
  }

  if (!age) {
    return res.status(400).json({ error: "Missing or invalid field: age" });
  }

  return res.json({
    status: "success",
    message: "Order created",
    data: { name, age }
  });
});

app.listen(8081, () => {
  console.log("Local test server running on port 8081");
});