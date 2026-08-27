package io.akka.redash.api;

import akka.http.javadsl.model.HttpEntity;
import io.akka.redash.domain.Json;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What every server-rendered page shares: the form it was posted, the context its template
 * expects, and the reduction a `next` parameter goes through (SPEC-001 R18).
 */
final class Pages {

  private Pages() {}

  /**
   * A posted form as a map, in either of the two encodings a browser sends.
   *
   * <p>Both, because the original's framework accepts both and things do send the other one:
   * redash's own end-to-end suite seeds an instance by posting `/setup` and `/login` as
   * `multipart/form-data`, and against a rebuild that read only the url-encoded form every
   * one of its specs failed at the first step with the cross-site token reported missing.
   * The original answers 302 to either (question-log row 111).
   */
  static Map<String, String> form(HttpEntity.Strict entity) {
    if (entity == null) {
      return new LinkedHashMap<>();
    }
    var boundary = multipartBoundary(entity.getContentType().toString());
    if (boundary != null) {
      return multipart(entity.getData().utf8String(), boundary);
    }
    return AuthEndpoint.decode(entity.getData().utf8String());
  }

  /** The boundary a multipart content type names, or null when it is not one. */
  private static String multipartBoundary(String contentType) {
    if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT)
        .startsWith("multipart/form-data")) {
      return null;
    }
    for (String parameter : contentType.split(";")) {
      var trimmed = parameter.strip();
      if (trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("boundary=")) {
        var value = trimmed.substring("boundary=".length()).strip();
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
          value = value.substring(1, value.length() - 1);
        }
        return value;
      }
    }
    return null;
  }

  /**
   * The named parts of a multipart body, as text.
   *
   * <p>Only the fields a form carries: a part with a filename is a file upload, and nothing
   * this rebuild serves takes one — the two routes that read a form are the sign-in and the
   * install page. A part is `headers`, a blank line, then its content up to the next
   * boundary, with the trailing line break belonging to the boundary rather than the value.
   */
  private static Map<String, String> multipart(String body, String boundary) {
    var out = new LinkedHashMap<String, String>();
    var delimiter = "--" + boundary;
    for (String part : body.split(java.util.regex.Pattern.quote(delimiter))) {
      int width = 4;
      int blank = part.indexOf("\r\n\r\n");
      if (blank < 0) {
        blank = part.indexOf("\n\n");
        width = 2;
      }
      if (blank < 0) {
        continue;
      }
      var name = fieldName(part.substring(0, blank));
      if (name == null) {
        continue;
      }
      var value = part.substring(blank + width);
      // The line break before the next boundary belongs to the boundary, and the two
      // hyphens after the last one belong to the body's end rather than to a value.
      while (value.endsWith("\n") || value.endsWith("\r") || value.endsWith("-")) {
        value = value.substring(0, value.length() - 1);
      }
      out.put(name, value);
    }
    return out;
  }

  /** The `name=` of a part's content disposition, when it is a plain field. */
  private static String fieldName(String headers) {
    var lower = headers.toLowerCase(java.util.Locale.ROOT);
    int at = lower.indexOf("name=\"");
    if (at < 0 || lower.contains("filename=")) {
      return null;
    }
    int start = at + "name=\"".length();
    int end = headers.indexOf('"', start);
    return end < 0 ? null : headers.substring(start, end);
  }

  /**
   * Reduce a `next` parameter to a same-site path (SPEC-001 R18).
   *
   * <p>This follows the source line for line, because the shapes it lets through are not the
   * ones a summary of it would predict. `https://example.com/queries` with a start-of-heading byte in front of it is
   * not rejected
   * for its control character: the parser underneath strips leading control characters
   * before it looks at anything, so what is judged is `https://example.com/queries`, whose
   * path is kept. The reduction was recorded from the running original over fourteen shapes
   * (`probes/probe_17_login.py`), and those recorded answers are what this is compared
   * against.
   *
   * <p>An absent parameter is the index — the source defaults the lookup to it — while a
   * parameter that is present and empty reduces to the empty string, which is a different
   * answer and a visible one: the redirect carries no location at all.
   */
  static String nextPath(String value) {
    if (value == null) {
      value = "/";
    }
    if (value.isEmpty()) {
      return "";
    }
    if (isSameSite(value)) {
      return value;
    }
    var parts = Split.of(value);
    if (!parts.scheme.isEmpty() && !parts.scheme.equals("http")
        && !parts.scheme.equals("https")) {
      return "./";
    }
    if (parts.netloc.isEmpty()) {
      return "./";
    }
    var safe = parts.path
        + (parts.query.isEmpty() ? "" : "?" + parts.query)
        + (parts.fragment.isEmpty() ? "" : "#" + parts.fragment);
    return !safe.isEmpty() && isSameSite(safe) ? safe : "./";
  }

  /**
   * Whether an address is already safe to be sent to unchanged.
   *
   * <p>The backslash pass is not redundant: a browser reads a backslash-slash prefix as `//evil.com`,
   * so an address that is same-site only until its backslashes are read as slashes is not
   * same-site.
   */
  private static boolean isSameSite(String url) {
    if (url.isEmpty()) {
      return false;
    }
    url = url.strip();
    if (url.isEmpty()) {
      return false;
    }
    var kind = Character.getType(url.charAt(0));
    if (kind == Character.CONTROL || kind == Character.FORMAT || kind == Character.SURROGATE
        || kind == Character.PRIVATE_USE || kind == Character.UNASSIGNED) {
      return false;
    }
    for (String candidate : List.of(url, url.replace("\\", "/"))) {
      if (candidate.startsWith("///")) {
        return false;
      }
      var parts = Split.of(candidate);
      if (!parts.scheme.isEmpty() && parts.netloc.isEmpty()) {
        return false;
      }
      if (!parts.netloc.isEmpty()) {
        return false;
      }
      if (!parts.scheme.isEmpty() && !parts.scheme.equals("http")
          && !parts.scheme.equals("https")) {
        return false;
      }
    }
    return true;
  }

  /**
   * An address split the way the source's own parser splits it.
   *
   * <p>Two of its habits decide answers here and neither is obvious: leading characters at
   * or below a space are removed before parsing, and tabs and line breaks are removed from
   * anywhere in the address. A scheme is only recognised when everything before the colon
   * begins with a letter and is made of letters, digits, `+`, `-` and `.`.
   */
  private record Split(String scheme, String netloc, String path, String query,
                       String fragment) {

    static Split of(String url) {
      var text = new StringBuilder();
      for (char c : url.toCharArray()) {
        if (c != '\t' && c != '\r' && c != '\n') {
          text.append(c);
        }
      }
      int from = 0;
      int to = text.length();
      while (from < to && text.charAt(from) <= 0x20) {
        from++;
      }
      while (to > from && text.charAt(to - 1) <= 0x20) {
        to--;
      }
      var rest = text.substring(from, to);

      var scheme = "";
      int colon = rest.indexOf(':');
      if (colon > 0 && Character.isLetter(rest.charAt(0)) && rest.charAt(0) < 128) {
        var candidate = rest.substring(0, colon);
        var usable = true;
        for (char c : candidate.toCharArray()) {
          if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') {
            usable = false;
            break;
          }
        }
        if (usable) {
          scheme = candidate.toLowerCase(java.util.Locale.ROOT);
          rest = rest.substring(colon + 1);
        }
      }

      var netloc = "";
      if (rest.startsWith("//")) {
        var end = rest.length();
        for (int i = 2; i < rest.length(); i++) {
          if (rest.charAt(i) == '/' || rest.charAt(i) == '?' || rest.charAt(i) == '#') {
            end = i;
            break;
          }
        }
        netloc = rest.substring(2, end);
        rest = rest.substring(end);
      }

      var fragment = "";
      int hash = rest.indexOf('#');
      if (hash >= 0) {
        fragment = rest.substring(hash + 1);
        rest = rest.substring(0, hash);
      }

      var query = "";
      int mark = rest.indexOf('?');
      if (mark >= 0) {
        query = rest.substring(mark + 1);
        rest = rest.substring(0, mark);
      }

      return new Split(scheme, netloc, rest, query, fragment);
    }
  }

  /** The context every template gets, before the page adds its own. */
  static Map<String, Object> base(Service service, Map<String, Object> extra) {
    var out = new LinkedHashMap<String, Object>();
    out.put("csrf_token", Jinja.helper(ignored -> Sessions.csrfToken(service.settings())));
    out.put("get_flashed_messages", Jinja.helper(ignored -> List.of()));
    out.put("org_slug", service.currentOrg() == null
        ? "default" : service.currentOrg().get("slug"));
    out.put("base_href", service.baseUrl() + "/");
    out.put("hide_page_header", false);
    out.put("url_for", Jinja.helper(arguments -> route(service, arguments)));
    out.put("asset_url", Jinja.helper(arguments -> {
      var positional = arguments.get("");
      var wanted = positional instanceof List<?> list && !list.isEmpty()
          ? String.valueOf(list.get(0)) : "";
      return "/static/" + Assets.resolve(wanted);
    }));
    out.putAll(extra);
    return out;
  }

  /**
   * The address of a named route, which is what the source's templates ask for by name.
   *
   * <p>Eight calls appear across the shipped templates and they name five routes. A route
   * that is not one of them answers `/`, because every one of those five is a page this
   * rebuild serves and an unknown name would be a template the rebuild does not ship.
   * `_external=True` asks for the whole address rather than the path — the invitation email
   * uses it, and a relative link in an email goes nowhere.
   */
  private static String route(Service service, Map<String, Object> arguments) {
    var positional = arguments.get("");
    var name = positional instanceof List<?> list && !list.isEmpty()
        ? String.valueOf(list.get(0)) : "";
    var path = switch (name) {
      case "static" -> "/" + arguments.getOrDefault("filename", "");
      case "redash.index" -> "/";
      case "redash.forgot_password" -> "/forgot";
      case "saml_auth.sp_initiated" -> "/saml/login";
      case "remote_user_auth.login" -> "/remote_user/login";
      case "ldap_auth.login" -> "/ldap/login";
      default -> "/";
    };
    var next = arguments.get("next");
    if (next != null && !String.valueOf(next).isEmpty()) {
      path = path + "?next=" + java.net.URLEncoder.encode(String.valueOf(next),
          java.nio.charset.StandardCharsets.UTF_8);
    }
    return Boolean.TRUE.equals(arguments.get("_external")) ? service.baseUrl() + path : path;
  }

  /** The page a broken link lands on. */
  static String error(Service service, String message) {
    return service.templates().render("error.html",
        base(service, Json.map("error_message", message)));
  }

  /** Percent-decoding for a single value, used by the form decoder and the tests. */
  static String decodeValue(String value) {
    return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
