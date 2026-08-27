package io.akka.redash.api;

import io.akka.redash.domain.Settings;
import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

/**
 * Signing in against a directory (SPEC-001 R19).
 *
 * <p>The two-step shape is the source's: bind as the service account (or anonymously),
 * search for the entry whose template matches the name given, then **rebind as that entry**
 * with the password supplied. A directory that answers the search but refuses the rebind is
 * a wrong password, which is why the second bind is what decides.
 */
final class Ldap {

  /** What a successful sign-in yields: the two attributes the source reads back. */
  record Found(String email, String displayName) {}

  private final Settings settings;

  Ldap(Settings settings) {
    this.settings = settings;
  }

  Found authenticate(String username, String password) {
    var url = settings.ldapUrl();
    var searchDn = settings.ldapSearchDn();
    if (url == null || url.isEmpty() || searchDn == null || searchDn.isEmpty()) {
      return null;
    }
    var displayNameKey = settings.ldapDisplayNameKey();
    var emailKey = settings.ldapEmailKey();
    var template = settings.ldapSearchTemplate();
    var filter = template.replace("%(username)s", escape(username));

    try {
      var environment = new Hashtable<String, Object>();
      environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
      environment.put(Context.PROVIDER_URL, url);
      if (settings.ldapUseSsl()) {
        environment.put(Context.SECURITY_PROTOCOL, "ssl");
      }
      var bindDn = settings.ldapBindDn();
      if (bindDn != null && !bindDn.isEmpty()) {
        // The directory's own word for the method, lower-cased: the source passes it
        // through to its client the same way, and `SIMPLE` is its default.
        environment.put(Context.SECURITY_AUTHENTICATION,
            settings.ldapAuthMethod().toLowerCase(java.util.Locale.ROOT));
        environment.put(Context.SECURITY_PRINCIPAL, bindDn);
        environment.put(Context.SECURITY_CREDENTIALS, settings.ldapBindDnPassword());
      } else {
        environment.put(Context.SECURITY_AUTHENTICATION, "none");
      }

      var context = new InitialDirContext(environment);
      try {
        var controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[] {displayNameKey, emailKey});
        NamingEnumeration<SearchResult> results = context.search(searchDn, filter, controls);
        if (!results.hasMore()) {
          return null;
        }
        var entry = results.next();
        var entryDn = entry.getNameInNamespace();
        var email = attribute(entry, emailKey);
        var displayName = attribute(entry, displayNameKey);

        var rebind = new Hashtable<String, Object>(environment);
        rebind.put(Context.SECURITY_AUTHENTICATION, "simple");
        rebind.put(Context.SECURITY_PRINCIPAL, entryDn);
        rebind.put(Context.SECURITY_CREDENTIALS, password);
        var bound = new InitialDirContext(rebind);
        bound.close();
        return new Found(email, displayName);
      } finally {
        context.close();
      }
    } catch (Exception e) {
      return null;
    }
  }

  private static String attribute(SearchResult entry, String name) throws Exception {
    var attribute = entry.getAttributes().get(name);
    return attribute == null ? null : String.valueOf(attribute.get());
  }

  /** RFC 4515 escaping, so a name carrying a filter character cannot change the filter. */
  static String escape(String value) {
    var out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '*' -> out.append("\\2a");
        case '(' -> out.append("\\28");
        case ')' -> out.append("\\29");
        case '\\' -> out.append("\\5c");
        case '\0' -> out.append("\\00");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
