package io.akka.redash.api;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * What a SAML assertion tells the rebuild (SPEC-001 R19).
 *
 * <p>Four values are read: the subject's name identifier, which is the address; the
 * `FirstName` and `LastName` attributes, which the source joins with a space to make the
 * display name; and `RedashGroups`, which reassigns the person's groups when it is present.
 * Nothing else in the assertion is used by the source, so nothing else is read here.
 */
final class Saml {

  /** The four values an assertion carries. */
  record Assertion(String email, String firstName, String lastName, List<String> groups) {}

  private static final String NAMESPACE = "urn:oasis:names:tc:SAML:2.0:assertion";

  private Saml() {}

  static Assertion read(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return null;
    }
    try {
      var xml = Base64.getMimeDecoder().decode(encoded.strip());
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      // An assertion arrives from outside, so no external entity is ever resolved.
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

      String email = null;
      var nameIds = document.getElementsByTagNameNS(NAMESPACE, "NameID");
      if (nameIds.getLength() > 0) {
        email = nameIds.item(0).getTextContent().strip();
      }

      String firstName = null;
      String lastName = null;
      var groups = new ArrayList<String>();
      var attributes = document.getElementsByTagNameNS(NAMESPACE, "Attribute");
      for (int i = 0; i < attributes.getLength(); i++) {
        var attribute = (Element) attributes.item(i);
        var name = attribute.getAttribute("Name");
        var values = valuesOf(attribute);
        switch (name) {
          case "FirstName" -> firstName = values.isEmpty() ? null : values.get(0);
          case "LastName" -> lastName = values.isEmpty() ? null : values.get(0);
          case "RedashGroups" -> groups.addAll(values);
          default -> { }
        }
      }
      return new Assertion(email, firstName, lastName, groups);
    } catch (Exception e) {
      return null;
    }
  }

  private static List<String> valuesOf(Element attribute) {
    var out = new ArrayList<String>();
    NodeList values = attribute.getElementsByTagNameNS(NAMESPACE, "AttributeValue");
    for (int i = 0; i < values.getLength(); i++) {
      Node value = values.item(i);
      out.add(value.getTextContent().strip());
    }
    return out;
  }

  /** Kept so a test can build an assertion without a directory server. */
  static String encode(String xml) {
    return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
  }
}
