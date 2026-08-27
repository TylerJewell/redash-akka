package io.akka.redash.api;

import io.akka.redash.domain.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * A result written as a file: comma-separated, tab-separated or a spreadsheet
 * (SPEC-001 R86, R88, R89).
 *
 * <p>Three details are the source's and are observable in a byte comparison. Rows end with
 * a carriage return and a line feed, because Python's csv writer's default dialect does. A
 * value is quoted only when it carries the delimiter, a quote or a newline. And a key in a
 * row that is not one of the declared columns is ignored rather than written.
 */
final class Downloads {

  private Downloads() {}

  static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy_MM_dd")
      .withZone(ZoneOffset.UTC);

  /** Delimiter-separated values, with the organisation's own date and time formats. */
  static String dsv(Map<String, Object> data, char delimiter, String dateFormat,
      String timeFormat) {
    var columns = Json.asList(data.get("columns"));
    var names = new ArrayList<String>();
    var converters = new LinkedHashMap<String, String>();
    for (Object column : columns) {
      var declared = Json.asMap(column);
      var name = String.valueOf(declared.get("name"));
      names.add(name);
      var type = String.valueOf(declared.get("type"));
      if ("boolean".equals(type) || "date".equals(type) || "datetime".equals(type)) {
        converters.put(name, type);
      }
    }

    var out = new StringBuilder();
    writeRow(out, names, delimiter);
    for (Object row : Json.asList(data.get("rows"))) {
      var values = Json.asMap(row);
      var cells = new ArrayList<String>(names.size());
      for (String name : names) {
        var value = values.get(name);
        var type = converters.get(name);
        if (type != null) {
          value = convert(value, type, dateFormat, timeFormat);
        }
        cells.add(value == null ? "" : String.valueOf(value));
      }
      writeRow(out, cells, delimiter);
    }
    return out.toString();
  }

  static Object convert(Object value, String type, String dateFormat, String timeFormat) {
    return switch (type) {
      case "boolean" -> Boolean.TRUE.equals(value) ? "true"
          : Boolean.FALSE.equals(value) ? "false" : value;
      case "date" -> formatInstant(value, pythonFormat(dateFormat));
      case "datetime" -> formatInstant(value, pythonFormat(dateFormat + " " + timeFormat));
      default -> value;
    };
  }

  /** The organisation's format string, translated to the one `strftime` would produce. */
  static String pythonFormat(String format) {
    return format
        .replace("DD", "dd")
        .replace("MM", "MM")
        .replace("YYYY", "yyyy")
        .replace("YY", "yy")
        .replace("HH", "HH")
        .replace("mm", "mm")
        .replace("ss", "ss")
        .replace("SSS", "SSS");
  }

  static Object formatInstant(Object value, String pattern) {
    if (value == null || String.valueOf(value).isEmpty()) {
      return value;
    }
    try {
      var parsed = java.time.OffsetDateTime.parse(String.valueOf(value));
      return DateTimeFormatter.ofPattern(pattern).format(parsed);
    } catch (RuntimeException first) {
      try {
        var parsed = java.time.LocalDateTime.parse(String.valueOf(value));
        return DateTimeFormatter.ofPattern(pattern).format(parsed);
      } catch (RuntimeException second) {
        try {
          var parsed = java.time.LocalDate.parse(String.valueOf(value));
          return DateTimeFormatter.ofPattern(pattern).format(parsed.atStartOfDay());
        } catch (RuntimeException third) {
          return value;
        }
      }
    }
  }

  private static void writeRow(StringBuilder out, List<String> cells, char delimiter) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        out.append(delimiter);
      }
      out.append(quote(cells.get(i), delimiter));
    }
    out.append("\r\n");
  }

  static String quote(String value, char delimiter) {
    boolean needed = value.indexOf(delimiter) >= 0 || value.indexOf('"') >= 0
        || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    if (!needed) {
      return value;
    }
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  /**
   * A single-sheet spreadsheet named `result` (SPEC-001 R89).
   *
   * <p>Written directly rather than through a library: the file is a zip of five small XML
   * parts, and a dependency that writes them would be a larger surface than the parts.
   */
  static byte[] xlsx(Map<String, Object> data) {
    var columns = Json.asList(data.get("columns"));
    var names = new ArrayList<String>();
    for (Object column : columns) {
      names.add(String.valueOf(Json.asMap(column).get("name")));
    }
    var rows = new ArrayList<List<Object>>();
    for (Object row : Json.asList(data.get("rows"))) {
      var values = Json.asMap(row);
      var cells = new ArrayList<>();
      for (String name : names) {
        var value = values.get(name);
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
          value = Json.dumps(value);
        }
        cells.add(value);
      }
      rows.add(cells);
    }

    var sheet = new StringBuilder();
    sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        .append("<sheetData>");
    sheet.append(sheetRow(1, names));
    for (int r = 0; r < rows.size(); r++) {
      sheet.append(sheetRow(r + 2, rows.get(r)));
    }
    sheet.append("</sheetData></worksheet>");

    var parts = new LinkedHashMap<String, byte[]>();
    parts.put("[Content_Types].xml", bytes(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package"
        + ".relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd."
        + "openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
        + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd."
        + "openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>"));
    parts.put("_rels/.rels", bytes(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument"
        + "/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"));
    parts.put("xl/workbook.xml", bytes(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
        + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
        + "<sheets><sheet name=\"result\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"));
    parts.put("xl/_rels/workbook.xml.rels", bytes(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument"
        + "/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>"));
    parts.put("xl/worksheets/sheet1.xml", bytes(sheet.toString()));
    return zip(parts);
  }

  private static String sheetRow(int index, List<?> cells) {
    var out = new StringBuilder("<row r=\"" + index + "\">");
    for (int c = 0; c < cells.size(); c++) {
      var reference = columnName(c) + index;
      var value = cells.get(c);
      if (value == null) {
        continue;
      }
      if (value instanceof Number number) {
        out.append("<c r=\"").append(reference).append("\"><v>").append(number).append("</v></c>");
      } else if (value instanceof Boolean flag) {
        out.append("<c r=\"").append(reference).append("\" t=\"b\"><v>")
            .append(flag ? 1 : 0).append("</v></c>");
      } else {
        out.append("<c r=\"").append(reference).append("\" t=\"inlineStr\"><is><t>")
            .append(escapeXml(String.valueOf(value))).append("</t></is></c>");
      }
    }
    return out.append("</row>").toString();
  }

  static String columnName(int index) {
    var out = new StringBuilder();
    int n = index;
    while (true) {
      out.insert(0, (char) ('A' + n % 26));
      n = n / 26 - 1;
      if (n < 0) {
        break;
      }
    }
    return out.toString();
  }

  private static String escapeXml(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  /** A stored zip archive, written by hand so the parts land in a fixed order. */
  private static byte[] zip(Map<String, byte[]> parts) {
    var out = new ByteArrayOutputStream();
    var directory = new ByteArrayOutputStream();
    int offset = 0;
    int count = 0;
    try {
      for (var entry : parts.entrySet()) {
        var name = entry.getKey().getBytes(StandardCharsets.UTF_8);
        var content = entry.getValue();
        var crc = new CRC32();
        crc.update(content);
        var deflated = deflate(content);

        var local = new ByteArrayOutputStream();
        writeInt(local, 0x04034b50);
        writeShort(local, 20);
        writeShort(local, 0);
        writeShort(local, 8);
        writeShort(local, 0);
        writeShort(local, 0);
        writeInt(local, (int) crc.getValue());
        writeInt(local, deflated.length);
        writeInt(local, content.length);
        writeShort(local, name.length);
        writeShort(local, 0);
        local.write(name);
        local.write(deflated);
        var localBytes = local.toByteArray();

        writeInt(directory, 0x02014b50);
        writeShort(directory, 20);
        writeShort(directory, 20);
        writeShort(directory, 0);
        writeShort(directory, 8);
        writeShort(directory, 0);
        writeShort(directory, 0);
        writeInt(directory, (int) crc.getValue());
        writeInt(directory, deflated.length);
        writeInt(directory, content.length);
        writeShort(directory, name.length);
        writeShort(directory, 0);
        writeShort(directory, 0);
        writeShort(directory, 0);
        writeShort(directory, 0);
        writeInt(directory, 0);
        writeInt(directory, offset);
        directory.write(name);

        out.write(localBytes);
        offset += localBytes.length;
        count++;
      }
      var directoryBytes = directory.toByteArray();
      out.write(directoryBytes);
      var end = new ByteArrayOutputStream();
      writeInt(end, 0x06054b50);
      writeShort(end, 0);
      writeShort(end, 0);
      writeShort(end, count);
      writeShort(end, count);
      writeInt(end, directoryBytes.length);
      writeInt(end, offset);
      writeShort(end, 0);
      out.write(end.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException("the spreadsheet could not be written", e);
    }
    return out.toByteArray();
  }

  private static byte[] deflate(byte[] content) {
    var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    deflater.setInput(content);
    deflater.finish();
    var out = new ByteArrayOutputStream();
    var buffer = new byte[4096];
    while (!deflater.finished()) {
      int written = deflater.deflate(buffer);
      out.write(buffer, 0, written);
    }
    deflater.end();
    return out.toByteArray();
  }

  private static void writeInt(ByteArrayOutputStream out, int value) {
    out.write(value & 0xff);
    out.write((value >> 8) & 0xff);
    out.write((value >> 16) & 0xff);
    out.write((value >> 24) & 0xff);
  }

  private static void writeShort(ByteArrayOutputStream out, int value) {
    out.write(value & 0xff);
    out.write((value >> 8) & 0xff);
  }

  /** The filename a download is offered under (SPEC-001 R86). */
  static String filename(Map<String, Object> result, Map<String, Object> query, String filetype) {
    var retrievedAt = Service.instant(result.get("retrieved_at"));
    var day = DAY.format(retrievedAt == null ? Instant.now() : retrievedAt);
    String base;
    if (query != null) {
      var name = io.akka.redash.domain.Text.stripControl(
          String.valueOf(query.getOrDefault("name", "")));
      base = name.isEmpty() ? String.valueOf(query.get("id"))
          : io.akka.redash.domain.Text.toFilename(name);
    } else {
      base = String.valueOf(result.get("id"));
    }
    return base + "_" + day + "." + filetype;
  }
}
