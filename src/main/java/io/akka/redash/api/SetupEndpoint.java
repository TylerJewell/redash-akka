package io.akka.redash.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.domain.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `/setup` — the one page an empty instance shows (SPEC-001 R1, R21).
 *
 * <p>It answers a redirect once an organisation exists, so it can only ever be used once.
 * The four validations are the source's form: a name and an organisation name are required,
 * the address must look like one, and the password must be at least six characters.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/setup")
public class SetupEndpoint extends ApiBase {

  /** These are pages, so a refusal made before any handler runs answers HTML. */
  @Override
  protected boolean rendersPages() {
    return true;
  }


  public SetupEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("")
  public HttpResponse page() {
    return answer(() -> {
      if (service.isSetUp() || service.settings().multiOrg()) {
        return Http.redirect("/");
      }
      return Http.html(render(Map.of(), List.of()));
    });
  }

  @Post("")
  public HttpResponse submit(HttpEntity.Strict entity) {
    return answer(() -> {
      guardCsrf(entity);
      if (service.isSetUp() || service.settings().multiOrg()) {
        return Http.redirect("/");
      }
      var form = Pages.form(entity);
      var errors = validate(form);
      if (!errors.isEmpty()) {
        return Http.html(render(form, errors));
      }
      var created = service.setup(form.get("org_name"), form.get("name"), form.get("email"),
          form.get("password"));
      var user = Json.asMap(created.get("user"));
      return new AuthEndpoint(store().client())
          .signedIn(user, "/", false, header("User-Agent"), remoteAddress());
    });
  }

  static List<String> validate(Map<String, String> form) {
    var errors = new ArrayList<String>();
    if (blank(form.get("name"))) {
      errors.add("This field is required.");
    }
    var email = form.get("email");
    if (blank(email) || !email.contains("@") || email.startsWith("@") || email.endsWith("@")) {
      errors.add("Invalid email address.");
    }
    var password = form.get("password");
    if (password == null || password.length() < 6) {
      errors.add("Field must be at least 6 characters long.");
    }
    if (blank(form.get("org_name"))) {
      errors.add("This field is required.");
    }
    return errors;
  }

  private static boolean blank(String value) {
    return value == null || value.strip().isEmpty();
  }

  private String render(Map<String, String> form, List<String> errors) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("name", new FormField("name", "Name", "text",
        form.getOrDefault("name", ""), List.of()));
    fields.put("email", new FormField("email", "Email Address", "email",
        form.getOrDefault("email", ""), List.of()));
    fields.put("password", new FormField("password", "Password", "password", "", List.of()));
    fields.put("org_name", new FormField("org_name", "Organization Name", "text",
        form.getOrDefault("org_name", ""), List.of()));
    fields.put("security_notifications",
        new FormField("security_notifications", "", "checkbox", true, List.of()));
    fields.put("newsletter", new FormField("newsletter", "", "checkbox", true, List.of()));
    return service.templates().render("setup.html",
        Pages.base(service, Json.map("form", fields,
            "get_flashed_messages", Jinja.helper(ignored -> errors))));
  }
}
