package io.akka.redash.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What happens to query text before anybody runs it (SPEC-001 R79, R80, R65).
 *
 * <p>Three jobs, all of them driven by one tokeniser: cutting a script into statements,
 * putting an automatic row limit on the last one, and reformatting text for the editor.
 *
 * <p>The order the two strippers run in is the whole of why
 * {@code "SELECT 1\n-- c\n;\nSELECT 2"} keeps its comment: the comment stripper walks back
 * from the end and stops at the semicolon, and only then does the semicolon stripper
 * remove it. Reverse them and the comment goes too.
 */
public final class Sql {

  /** One lexical piece of a statement. */
  public record Token(Kind kind, String text) {
    public boolean isWhitespace() {
      return kind == Kind.WHITESPACE;
    }

    public boolean isComment() {
      return kind == Kind.COMMENT;
    }
  }

  public enum Kind {
    WHITESPACE,
    COMMENT,
    STRING,
    NUMBER,
    KEYWORD,
    NAME,
    PUNCTUATION,
    OPERATOR
  }

  /**
   * The words the limit rule and the formatter treat as keywords. It is not every SQL word
   * in existence and does not need to be: the two rules that read it ask only whether the
   * **last** keyword of a statement is one of a small set, and which words start a line.
   */
  private static final Set<String> KEYWORDS = Set.of(
      "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET", "TOP",
      "FETCH", "NEXT", "ROW", "ROWS", "ONLY", "TIES", "JOIN", "INNER", "LEFT", "RIGHT", "FULL",
      "CROSS", "OUTER", "ON", "AS", "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "ILIKE",
      "BETWEEN", "UNION", "ALL", "INTERSECT", "EXCEPT", "DISTINCT", "INSERT", "INTO", "VALUES",
      "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "VIEW", "DROP", "ALTER", "WITH", "CASE",
      "WHEN", "THEN", "ELSE", "END", "EXISTS", "ASC", "DESC", "USING", "NATURAL", "OVER",
      "PARTITION", "WINDOW", "RETURNING", "TRUNCATE", "GRANT", "REVOKE", "EXPLAIN", "ANALYZE");

  /** The keywords that start their own line when the text is reindented. */
  private static final Set<String> LINE_STARTERS = Set.of(
      "FROM", "WHERE", "HAVING", "LIMIT", "OFFSET", "UNION", "INTERSECT", "EXCEPT", "VALUES",
      "SET", "RETURNING", "WINDOW");

  private Sql() {}

  // ---------------------------------------------------------------- tokenising

  public static List<Token> tokenize(String text) {
    var out = new ArrayList<Token>();
    int i = 0;
    int n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) {
        int start = i;
        while (i < n && Character.isWhitespace(text.charAt(i))) {
          i++;
        }
        out.add(new Token(Kind.WHITESPACE, text.substring(start, i)));
      } else if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
        int start = i;
        while (i < n && text.charAt(i) != '\n') {
          i++;
        }
        // The newline belongs to the comment, the way a line comment's token does.
        if (i < n) {
          i++;
        }
        out.add(new Token(Kind.COMMENT, text.substring(start, i)));
      } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
        int start = i;
        i += 2;
        while (i + 1 < n && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
          i++;
        }
        i = Math.min(n, i + 2);
        out.add(new Token(Kind.COMMENT, text.substring(start, i)));
      } else if (c == '\'' || c == '"' || c == '`') {
        int start = i;
        char quote = c;
        i++;
        while (i < n) {
          if (text.charAt(i) == '\\' && i + 1 < n) {
            i += 2;
            continue;
          }
          if (text.charAt(i) == quote) {
            // A doubled quote inside a string is an escaped quote, not the end of it.
            if (i + 1 < n && text.charAt(i + 1) == quote) {
              i += 2;
              continue;
            }
            i++;
            break;
          }
          i++;
        }
        out.add(new Token(Kind.STRING, text.substring(start, i)));
      } else if (Character.isDigit(c)) {
        int start = i;
        while (i < n && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) {
          i++;
        }
        out.add(new Token(Kind.NUMBER, text.substring(start, i)));
      } else if (Character.isLetter(c) || c == '_' || c == '$' || c == '#') {
        int start = i;
        while (i < n
            && (Character.isLetterOrDigit(text.charAt(i))
                || text.charAt(i) == '_'
                || text.charAt(i) == '$'
                || text.charAt(i) == '#')) {
          i++;
        }
        var word = text.substring(start, i);
        var kind = KEYWORDS.contains(word.toUpperCase(Locale.ROOT)) ? Kind.KEYWORD : Kind.NAME;
        out.add(new Token(kind, word));
      } else if (";,()[]".indexOf(c) >= 0) {
        out.add(new Token(Kind.PUNCTUATION, String.valueOf(c)));
        i++;
      } else {
        int start = i;
        while (i < n && "+-*/%<>=!|&^~".indexOf(text.charAt(i)) >= 0) {
          i++;
        }
        if (i == start) {
          i++;
        }
        out.add(new Token(Kind.OPERATOR, text.substring(start, i)));
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- splitting

  /** Cut a script on top-level semicolons, keeping each semicolon with what precedes it. */
  static List<String> rawStatements(String script) {
    var out = new ArrayList<String>();
    var current = new StringBuilder();
    for (Token token : tokenize(script)) {
      current.append(token.text());
      if (token.kind() == Kind.PUNCTUATION && ";".equals(token.text())) {
        out.add(current.toString());
        current.setLength(0);
      }
    }
    if (current.length() > 0) {
      out.add(current.toString());
    }
    return out;
  }

  public static List<String> splitStatements(String script) {
    var out = new ArrayList<String>();
    for (String raw : rawStatements(script)) {
      var tokens = new ArrayList<>(tokenize(raw));
      stripTrailingComments(tokens);
      stripTrailingSemicolon(tokens);
      var statement = join(tokens).strip();
      if (!isOnlyComments(raw)) {
        out.add(statement);
      }
    }
    return out.isEmpty() ? List.of("") : out;
  }

  private static void stripTrailingComments(List<Token> tokens) {
    for (int i = tokens.size() - 1; i >= 0; i--) {
      var token = tokens.get(i);
      if (token.isWhitespace() || token.isComment()) {
        tokens.set(i, new Token(Kind.WHITESPACE, " "));
      } else {
        break;
      }
    }
  }

  private static void stripTrailingSemicolon(List<Token> tokens) {
    for (int i = tokens.size() - 1; i >= 0; i--) {
      var token = tokens.get(i);
      if (token.isWhitespace()) {
        continue;
      }
      if (token.kind() == Kind.PUNCTUATION && ";".equals(token.text())) {
        tokens.set(i, new Token(Kind.WHITESPACE, " "));
      }
      break;
    }
  }

  private static boolean isOnlyComments(String raw) {
    for (Token token : tokenize(raw)) {
      if (token.isWhitespace() || token.isComment()) {
        continue;
      }
      if (token.kind() == Kind.PUNCTUATION && ";".equals(token.text())) {
        continue;
      }
      return false;
    }
    return true;
  }

  public static String combineStatements(List<String> statements) {
    return String.join(";\n", statements);
  }

  static String join(List<Token> tokens) {
    var out = new StringBuilder();
    for (Token token : tokens) {
      out.append(token.text());
    }
    return out.toString();
  }

  // ---------------------------------------------------------------- the automatic limit

  /** How one runner writes a row limit, since three shapes are in use across the registry. */
  public record LimitStyle(String limitQuery, List<String> limitKeywords, boolean afterSelect) {
    public static final LimitStyle TRAILING =
        new LimitStyle(" LIMIT 1000", List.of("LIMIT", "OFFSET"), false);
    public static final LimitStyle TOP =
        new LimitStyle(" TOP 1000", List.of("TOP"), true);
    public static final LimitStyle FETCH_NEXT =
        new LimitStyle(" FETCH NEXT 1000 ROWS ONLY", List.of("ROW", "ROWS", "ONLY", "TIES"), false);
  }

  /**
   * Whether the statement is a SELECT that has not already limited itself. The test on the
   * first token is a plain string comparison, so a statement beginning with `WITH` — a
   * perfectly ordinary SELECT — answers no and is never limited.
   */
  public static boolean isSelectWithoutLimit(String statement, LimitStyle style) {
    var tokens = tokenize(statement);
    if (tokens.isEmpty()) {
      return false;
    }
    int lastKeyword = -1;
    for (int i = tokens.size() - 1; i >= 0; i--) {
      if (tokens.get(i).kind() == Kind.KEYWORD) {
        lastKeyword = i;
        break;
      }
    }
    if (lastKeyword < 0) {
      return false;
    }
    if (!"SELECT".equals(tokens.get(0).text().toUpperCase(Locale.ROOT))) {
      return false;
    }
    return !style.limitKeywords().contains(tokens.get(lastKeyword).text().toUpperCase(Locale.ROOT));
  }

  public static String addLimit(String statement, LimitStyle style) {
    if (!style.afterSelect()) {
      return statement + style.limitQuery();
    }
    var tokens = tokenize(statement);
    for (int i = tokens.size() - 1; i >= 0; i--) {
      if ("SELECT".equals(tokens.get(i).text().toUpperCase(Locale.ROOT))) {
        var out = new StringBuilder();
        for (int j = 0; j <= i; j++) {
          out.append(tokens.get(j).text());
        }
        out.append(style.limitQuery());
        for (int j = i + 1; j < tokens.size(); j++) {
          out.append(tokens.get(j).text());
        }
        return out.toString();
      }
    }
    return statement;
  }

  /** The whole rule: split, limit the last statement only when asked and only when eligible. */
  public static String applyAutoLimit(String text, boolean shouldApply, LimitStyle style) {
    var statements = new ArrayList<>(splitStatements(text));
    if (shouldApply) {
      var last = statements.get(statements.size() - 1);
      if (isSelectWithoutLimit(last, style)) {
        statements.set(statements.size() - 1, addLimit(last, style));
      }
    }
    return combineStatements(statements);
  }

  // ---------------------------------------------------------------- formatting

  /**
   * What `/api/queries/format` answers: keywords upper-cased and the statement reindented
   * one clause to a line, with the columns of a select list aligned under the first.
   */
  public static String format(String text) {
    if (text == null || text.isBlank()) {
      return text == null ? "" : text.strip();
    }
    var tokens = tokenize(text);
    var out = new StringBuilder();
    int depth = 0;
    boolean inSelectList = false;
    var indent = " ".repeat("SELECT ".length());

    for (int i = 0; i < tokens.size(); i++) {
      var token = tokens.get(i);

      if (token.isWhitespace()) {
        // Whitespace between two tokens is left exactly as it was — the reindenter adds
        // line breaks, it does not respace anything. That is why `a=1` stays `a=1` and
        // `t.id = u.id` keeps both its spaces and its lack of them.
        if (startsLine(tokens, nextSignificant(tokens, i), depth)) {
          continue;
        }
        out.append(token.text());
        continue;
      }

      if (token.isComment()) {
        out.append(token.text().stripTrailing()).append("\n\n");
        i = skipFollowingWhitespace(tokens, i);
        continue;
      }

      var rendered =
          token.kind() == Kind.KEYWORD ? token.text().toUpperCase(Locale.ROOT) : token.text();

      if (token.kind() == Kind.PUNCTUATION) {
        if ("(".equals(rendered)) {
          depth++;
        } else if (")".equals(rendered)) {
          depth = Math.max(0, depth - 1);
        }
        out.append(rendered);
        if (",".equals(rendered) && depth == 0 && inSelectList) {
          out.append("\n").append(indent);
          i = skipFollowingWhitespace(tokens, i);
        }
        continue;
      }

      if (startsLine(tokens, i, depth) && out.length() > 0
          && out.charAt(out.length() - 1) != '\n') {
        out.append("\n");
      }
      if ("SELECT".equals(rendered)) {
        inSelectList = true;
      } else if (depth == 0 && LINE_STARTERS.contains(rendered)) {
        inSelectList = false;
      }
      out.append(rendered);
    }
    return out.toString().strip();
  }

  /** The index of the next token that is neither whitespace nor a comment, or -1. */
  private static int nextSignificant(List<Token> tokens, int from) {
    for (int i = from; i < tokens.size(); i++) {
      if (!tokens.get(i).isWhitespace() && !tokens.get(i).isComment()) {
        return i;
      }
    }
    return -1;
  }

  /** Move the cursor past the whitespace a just-emitted line break has replaced. */
  private static int skipFollowingWhitespace(List<Token> tokens, int index) {
    int i = index + 1;
    while (i < tokens.size() && tokens.get(i).isWhitespace()) {
      i++;
    }
    return i - 1;
  }

  private static boolean startsLine(List<Token> tokens, int index, int depth) {
    if (index < 0 || depth != 0) {
      return false;
    }
    var token = tokens.get(index);
    if (token.kind() != Kind.KEYWORD) {
      return false;
    }
    var word = token.text().toUpperCase(Locale.ROOT);
    return LINE_STARTERS.contains(word) || isJoinStart(tokens, index) || isByClause(tokens, index);
  }

  private static boolean isJoinStart(List<Token> tokens, int index) {
    var word = tokens.get(index).text().toUpperCase(Locale.ROOT);
    if ("JOIN".equals(word)) {
      // `LEFT JOIN` starts its line at LEFT, so JOIN itself does not start a second one.
      for (int i = index - 1; i >= 0; i--) {
        if (tokens.get(i).isWhitespace() || tokens.get(i).isComment()) {
          continue;
        }
        var previous = tokens.get(i).text().toUpperCase(Locale.ROOT);
        return !Set.of("INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER", "NATURAL")
            .contains(previous);
      }
      return true;
    }
    if (Set.of("INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL").contains(word)) {
      for (int i = index + 1; i < tokens.size(); i++) {
        if (tokens.get(i).isWhitespace() || tokens.get(i).isComment()) {
          continue;
        }
        var following = tokens.get(i).text().toUpperCase(Locale.ROOT);
        return "JOIN".equals(following) || "OUTER".equals(following);
      }
    }
    return false;
  }

  /** `ORDER BY` and `GROUP BY` start a line; a bare `BY` never does. */
  private static boolean isByClause(List<Token> tokens, int index) {
    var word = tokens.get(index).text().toUpperCase(Locale.ROOT);
    if (!"ORDER".equals(word) && !"GROUP".equals(word)) {
      return false;
    }
    for (int i = index + 1; i < tokens.size(); i++) {
      if (tokens.get(i).isWhitespace() || tokens.get(i).isComment()) {
        continue;
      }
      return "BY".equals(tokens.get(i).text().toUpperCase(Locale.ROOT));
    }
    return false;
  }
}
