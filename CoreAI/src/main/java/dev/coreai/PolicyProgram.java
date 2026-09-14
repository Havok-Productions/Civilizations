package dev.coreai;

import java.util.*;

/** A total, pure expression language. No host calls, loops, allocation instructions or IO. */
public final class PolicyProgram {
  public static final Set<String> FEATURES =
      Set.of(
          "base",
          "failures",
          "distance",
          "missing",
          "repair",
          "continuing",
          "food",
          "danger",
          "vertical",
          "detour");
  private static final Set<String> OPS = Set.of("+", "-", "*", "min", "max", ">", "<", "if");
  private final Node root;
  private final String source;

  private record Node(String token, List<Node> children) {
    double eval(Map<String, Double> f) {
      if (children.isEmpty())
        return FEATURES.contains(token) ? f.get(token) : Double.parseDouble(token);
      double a = children.get(0).eval(f);
      if (token.equals("if")) return children.get(a != 0 ? 1 : 2).eval(f);
      double b = children.get(1).eval(f);
      return clamp(
          switch (token) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "min" -> Math.min(a, b);
            case "max" -> Math.max(a, b);
            case ">" -> a > b ? 1 : 0;
            case "<" -> a < b ? 1 : 0;
            default -> throw new IllegalStateException("Unknown operator");
          });
    }
  }

  public static double clamp(double n) {
    return Math.clamp(n, -1_000_000, 1_000_000);
  }

  private PolicyProgram(String source, Node root) {
    this.source = source;
    this.root = root;
  }

  public String source() {
    return source;
  }

  public static PolicyProgram compile(String source) {
    if (source == null || source.isBlank() || source.length() > 4096)
      throw new IllegalArgumentException("Source limit: 4096 characters");
    var parser = new Parser(source);
    Node node = parser.node(0);
    if (parser.index != parser.tokens.length) throw new IllegalArgumentException("Trailing code");
    return new PolicyProgram(source, node);
  }

  public double score(Map<String, Double> features) {
    for (String key : FEATURES) {
      Double value = features.get(key);
      if (value == null || !Double.isFinite(value) || Math.abs(value) > 1_000_000)
        throw new IllegalArgumentException("Invalid feature: " + key);
    }
    return root.eval(features);
  }

  private static final class Parser {
    final String[] tokens;
    int index, nodes;

    Parser(String source) {
      tokens = source.replace("(", " ( ").replace(")", " ) ").trim().split("\\s+");
    }

    Node node(int depth) {
      if (depth > 16 || ++nodes > 128 || index >= tokens.length)
        throw new IllegalArgumentException("Policy structure limit or incomplete code");
      String token = tokens[index++];
      if (token.equals("(")) {
        if (index >= tokens.length || !OPS.contains(tokens[index]))
          throw new IllegalArgumentException("Unsupported instruction");
        String op = tokens[index++];
        List<Node> children = new ArrayList<>();
        for (int i = 0; i < (op.equals("if") ? 3 : 2); i++) children.add(node(depth + 1));
        if (index >= tokens.length || !tokens[index++].equals(")"))
          throw new IllegalArgumentException("Wrong instruction arity");
        return new Node(op, List.copyOf(children));
      }
      if (!FEATURES.contains(token)) {
        if (!token.matches("-?[0-9]{1,7}(\\.[0-9]{1,6})?"))
          throw new IllegalArgumentException("Unknown feature or literal");
        if (Math.abs(Double.parseDouble(token)) > 1_000_000)
          throw new IllegalArgumentException("Literal out of range");
      }
      return new Node(token, List.of());
    }
  }
}
