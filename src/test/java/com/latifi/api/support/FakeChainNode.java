package com.latifi.api.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in memory JSON-RPC node on the JDK HTTP server. It answers {@code eth_chainId}, {@code
 * eth_blockNumber}, {@code eth_getLogs} and {@code eth_getBlockByNumber}, the four calls the
 * indexer makes, and it records every request it served.
 *
 * <p>Details that web3j forces: JSON-RPC ids start at 0 and the answer copies the id, {@code
 * address} of {@code eth_getLogs} travels as an array, numbers are hex quantities, and {@code
 * topics[0]} may arrive as a string or as an array of alternatives.
 */
public class FakeChainNode {

  /** One request that the node served. {@code topicsJson} is empty for calls without a filter. */
  public record Request(String method, long from, long to, String topicsJson) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long BASE_EPOCH = 1_760_000_000L;

  private final HttpServer server;
  private final AtomicLong head = new AtomicLong(0);
  private final AtomicLong chainId = new AtomicLong(80002);
  private final List<Long> forkStarts = new CopyOnWriteArrayList<>();
  private final AtomicBoolean down = new AtomicBoolean(false);
  private final AtomicBoolean repeatLogs = new AtomicBoolean(false);
  private final AtomicInteger maxLogRange = new AtomicInteger(Integer.MAX_VALUE);
  private final AtomicInteger rejectedRanges = new AtomicInteger(0);
  private final AtomicInteger failAfter = new AtomicInteger(-1);
  private final AtomicInteger served = new AtomicInteger(0);
  private final Map<Long, List<ChainFixtures.Log>> logsByBlock = new ConcurrentHashMap<>();
  private final Map<Long, Long> timestamps = new ConcurrentHashMap<>();
  private final List<Request> requests = new CopyOnWriteArrayList<>();

  public FakeChainNode() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("the simulated node could not bind a port", e);
    }
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/", this::handle);
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void stop() {
    server.stop(0);
    ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
  }

  public long head() {
    return head.get();
  }

  /** Moves the head to {@code block} when it is higher than the current head. */
  public void mineTo(long block) {
    head.updateAndGet(current -> Math.max(current, block));
  }

  /** Places a log in {@code block}, mining up to that block when needed. */
  public void emit(long block, ChainFixtures.Log log) {
    mineTo(block);
    logsByBlock.computeIfAbsent(block, key -> new CopyOnWriteArrayList<>()).add(log);
  }

  /** Overrides the timestamp of one block. */
  public void timestamp(long block, long epochSeconds) {
    timestamps.put(block, epochSeconds);
  }

  public long timestampOf(long block) {
    return timestamps.getOrDefault(block, BASE_EPOCH + 2 * block);
  }

  public String hashOf(long block) {
    return blockHash(block);
  }

  public void chainId(long id) {
    chainId.set(id);
  }

  /** While true, every call answers HTTP 503. Setting it to false also disarms the countdown. */
  public void down(boolean value) {
    down.set(value);
    if (!value) {
      failAfter.set(-1);
    }
  }

  /**
   * The node answers {@code requests} more calls, then goes down like {@link #down(boolean)} with
   * true, so a test can stop it in the middle of a cycle. Every call counts, including {@code
   * eth_chainId} and {@code eth_blockNumber}.
   */
  public void failAfterRequests(int requests) {
    served.set(0);
    failAfter.set(requests);
  }

  /**
   * {@code eth_getLogs} for a range wider than {@code blocks} answers HTTP 200 with a JSON-RPC
   * error, the way a provider with a block range cap does.
   */
  public void maxLogRange(int blocks) {
    maxLogRange.set(blocks);
  }

  /** How many {@code eth_getLogs} ranges the node refused for being too wide. */
  public int rejectedRangeCount() {
    return rejectedRanges.get();
  }

  /**
   * The head falls to {@code block}, like a node that is behind or was restored from a snapshot.
   */
  public void truncateTo(long block) {
    head.set(block);
  }

  /** While true, {@code eth_getLogs} returns every log twice in one answer. */
  public void repeatLogs(boolean value) {
    repeatLogs.set(value);
  }

  /** Changes every block hash from now on, like a reorg that replaced all blocks. */
  public void forkHashes() {
    forkStarts.add(0L);
  }

  /**
   * A reorg from {@code fromBlock}: the logs of that block and every later block are gone, and the
   * hashes of those blocks change. The head stays where it is. A test then emits the logs of the
   * new branch and mines further.
   */
  public void reorg(long fromBlock) {
    logsByBlock.keySet().removeIf(number -> number >= fromBlock);
    forkStarts.add(fromBlock);
  }

  /** Removes every log and request and rewinds the chain. Tests call it between scenarios. */
  public void reset() {
    head.set(0);
    chainId.set(80002);
    forkStarts.clear();
    down.set(false);
    repeatLogs.set(false);
    maxLogRange.set(Integer.MAX_VALUE);
    rejectedRanges.set(0);
    failAfter.set(-1);
    served.set(0);
    logsByBlock.clear();
    timestamps.clear();
    requests.clear();
  }

  public List<Request> requests() {
    return List.copyOf(requests);
  }

  public List<Request> requests(String method) {
    return requests.stream().filter(request -> request.method().equals(method)).toList();
  }

  public void clearRequests() {
    requests.clear();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      byte[] body = exchange.getRequestBody().readAllBytes();
      if (failAfter.get() >= 0 && served.getAndIncrement() >= failAfter.get()) {
        down.set(true);
      }
      if (down.get()) {
        requests.add(new Request("down", -1, -1, ""));
        exchange.sendResponseHeaders(503, -1);
        return;
      }
      JsonNode request = JSON.readTree(body);
      ObjectNode response = JSON.createObjectNode();
      response.put("jsonrpc", "2.0");
      response.set("id", request.path("id"));
      String method = request.path("method").asText();
      JsonNode params = request.path("params");
      switch (method) {
        case "eth_chainId" -> {
          requests.add(new Request(method, -1, -1, ""));
          response.put("result", hex(chainId.get()));
        }
        case "eth_blockNumber" -> {
          requests.add(new Request(method, -1, -1, ""));
          response.put("result", hex(head.get()));
        }
        case "eth_getLogs" -> {
          long from = Long.decode(params.path(0).path("fromBlock").asText());
          long to = Long.decode(params.path(0).path("toBlock").asText());
          if (to - from + 1 > maxLogRange.get()) {
            requests.add(new Request(method, from, to, params.path(0).path("topics").toString()));
            rejectedRanges.incrementAndGet();
            ObjectNode error = response.putObject("error");
            error.put("code", -32602);
            error.put("message", "query exceeds max block range " + maxLogRange.get());
          } else {
            response.set("result", getLogs(params.path(0)));
          }
        }
        case "eth_getBlockByNumber" -> {
          long number = Long.decode(params.path(0).asText());
          requests.add(new Request(method, number, number, ""));
          response.set("result", number > head.get() ? JSON.nullNode() : block(number));
        }
        default -> {
          ObjectNode error = response.putObject("error");
          error.put("code", -32601);
          error.put("message", "method not found");
        }
      }
      byte[] out = JSON.writeValueAsBytes(response);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, out.length);
      try (OutputStream stream = exchange.getResponseBody()) {
        stream.write(out);
      }
    }
  }

  private JsonNode getLogs(JsonNode filter) {
    long from = Long.decode(filter.path("fromBlock").asText());
    long to = Long.decode(filter.path("toBlock").asText());
    JsonNode topics = filter.path("topics");
    requests.add(new Request("eth_getLogs", from, to, topics.toString()));

    Set<String> addresses = new HashSet<>();
    JsonNode addressNode = filter.path("address");
    if (addressNode.isArray()) {
      addressNode.forEach(a -> addresses.add(a.asText().toLowerCase(Locale.ROOT)));
    } else if (addressNode.isTextual()) {
      addresses.add(addressNode.asText().toLowerCase(Locale.ROOT));
    }
    Set<String> topic0 = new HashSet<>();
    JsonNode first = topics.path(0);
    if (first.isArray()) {
      first.forEach(t -> topic0.add(t.asText().toLowerCase(Locale.ROOT)));
    } else if (first.isTextual()) {
      topic0.add(first.asText().toLowerCase(Locale.ROOT));
    }

    ArrayNode result = JSON.createArrayNode();
    for (long number = from; number <= Math.min(to, head.get()); number++) {
      for (ChainFixtures.Log log : logsByBlock.getOrDefault(number, List.of())) {
        boolean addressMatches =
            addresses.isEmpty() || addresses.contains(log.address().toLowerCase(Locale.ROOT));
        boolean topicMatches = topic0.isEmpty() || topic0.contains(log.topics().get(0));
        if (addressMatches && topicMatches) {
          ObjectNode node = logNode(number, log);
          result.add(node);
          if (repeatLogs.get()) {
            result.add(node.deepCopy());
          }
        }
      }
    }
    return result;
  }

  private ObjectNode logNode(long number, ChainFixtures.Log log) {
    ObjectNode node = JSON.createObjectNode();
    node.put("removed", false);
    node.put("logIndex", hex(log.logIndex()));
    node.put("transactionIndex", "0x0");
    node.put("transactionHash", log.txHash());
    node.put("blockHash", blockHash(number));
    node.put("blockNumber", hex(number));
    node.put("address", log.address());
    node.put("data", log.data());
    ArrayNode topics = node.putArray("topics");
    log.topics().forEach(topics::add);
    return node;
  }

  private ObjectNode block(long number) {
    ObjectNode node = JSON.createObjectNode();
    node.put("number", hex(number));
    node.put("hash", blockHash(number));
    node.put("parentHash", blockHash(Math.max(0, number - 1)));
    node.put("timestamp", hex(timestampOf(number)));
    node.putArray("transactions");
    node.putArray("uncles");
    return node;
  }

  private String blockHash(long number) {
    try {
      long forks = forkStarts.stream().filter(start -> start <= number).count();
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(("block:" + number + ":" + forks).getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder("0x");
      for (byte b : digest) {
        out.append(String.format("%02x", b));
      }
      return out.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hex(long value) {
    return "0x" + Long.toHexString(value);
  }
}
