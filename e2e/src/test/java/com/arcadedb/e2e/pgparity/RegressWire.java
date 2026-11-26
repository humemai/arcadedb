/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.e2e.pgparity;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A client of the PostgreSQL protocol, version 3, as small as the regression harness needs it (fork issue #119). The harness
 * cannot use the JDBC driver for the statements of the regression files: the driver rewrites and splits statement text, ends the
 * connection when a statement changes {@code DateStyle} (several files do), and hides the command tag, the transaction state and
 * the data phase of {@code COPY}, which {@code psql} shows and the verdicts need. Every statement goes to the server as
 * {@code psql} sends it, and every value comes back as the text the server wrote. No password: the server of the harness trusts
 * its clients.
 */
final class RegressWire implements AutoCloseable {
  /** A column of an answer. */
  record Column(String name, int type) {
  }

  /** What the server answered to one statement. */
  static final class Reply {
    /** {@code null} when the statement returns no rows. */
    List<Column>   columns;
    List<String[]> rows = new ArrayList<>();
    /** The command tag ({@code INSERT 0 3}), {@code null} for an error and for an empty statement. */
    String         tag;
    String         sqlState;
    String         message;

    boolean failed() {
      return sqlState != null;
    }
  }

  private static final int TIMEOUT_MILLIS = 300_000;

  private final Socket              socket;
  private final DataInputStream     in;
  private final DataOutputStream    out;
  private final Map<String, String> parameters = new HashMap<>();
  /** {@code I} outside of a transaction block, {@code T} inside, {@code E} inside a failed one. */
  private       char                transactionState = 'I';

  RegressWire(final String host, final int port, final String user, final String database) throws IOException {
    socket = new Socket(host, port);
    socket.setSoTimeout(TIMEOUT_MILLIS);
    in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

    final Message startup = new Message().int32(196608).string("user").string(user).string("database").string(database)
        .string("client_encoding").string("UTF8").string("application_name").string("pgregress");
    startup.int8(0);
    out.writeInt(startup.size() + 4);
    out.write(startup.bytes());
    out.flush();

    final Reply reply = new Reply();
    while (true) {
      final int type = in.readUnsignedByte();
      final byte[] body = body();
      if (type == 'R' && int32(body, 0) != 0)
        throw new IOException("The server asks for a password: start it with trust authentication");
      else if (type == 'S')
        parameter(body);
      else if (type == 'E') {
        error(body, reply);
        throw new IOException("The server refused the connection: " + reply.sqlState + " " + reply.message);
      } else if (type == 'Z') {
        transactionState = (char) body[0];
        return;
      }
    }
  }

  char transactionState() {
    return transactionState;
  }

  /** A setting that the server reports, as it is now: {@code DateStyle}, {@code standard_conforming_strings}. */
  String parameter(final String name) {
    return parameters.get(name);
  }

  /** One statement through the simple protocol; {@code copyData} is asked for the data of a {@code COPY ... FROM STDIN}. */
  Reply query(final String sql, final Supplier<String> copyData) throws IOException {
    send('Q', new Message().string(sql));
    out.flush();
    return receive(copyData);
  }

  /** One statement with parameters through the extended protocol, as {@code \bind} sends it. */
  Reply query(final String sql, final List<String> values) throws IOException {
    send('P', new Message().string("").string(sql).int16(0));
    final Message bind = new Message().string("").string("").int16(0).int16(values.size());
    for (final String value : values) {
      final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      bind.int32(bytes.length).raw(bytes);
    }
    send('B', bind.int16(0));
    send('D', new Message().int8('P').string(""));
    send('E', new Message().string("").int32(0));
    send('S', new Message());
    out.flush();
    return receive(null);
  }

  private Reply receive(final Supplier<String> copyData) throws IOException {
    final Reply reply = new Reply();
    while (true) {
      final int type = in.readUnsignedByte();
      final byte[] body = body();
      switch (type) {
      case 'T' -> {
        reply.columns = columns(body);
        reply.rows = new ArrayList<>();
      }
      case 'D' -> reply.rows.add(row(body));
      case 'C' -> reply.tag = new String(body, 0, body.length - 1, StandardCharsets.UTF_8);
      case 'E' -> {
        if (reply.sqlState == null)
          error(body, reply);
        reply.tag = null;
      }
      case 'S' -> parameter(body);
      case 'G' -> {
        if (copyData == null)
          send('f', new Message().string("the harness has no data for this statement"));
        else {
          final byte[] data = copyData.get().getBytes(StandardCharsets.UTF_8);
          if (data.length > 0)
            send('d', new Message().raw(data));
          send('c', new Message());
        }
        out.flush();
      }
      case 'Z' -> {
        transactionState = (char) body[0];
        return reply;
      }
      default -> {
        // notices, notifications, the data of a COPY TO STDOUT, and the acknowledgements of the extended protocol
      }
      }
    }
  }

  private byte[] body() throws IOException {
    final int length = in.readInt();
    final byte[] body = new byte[length - 4];
    in.readFully(body);
    return body;
  }

  private void send(final char type, final Message message) throws IOException {
    out.writeByte(type);
    out.writeInt(message.size() + 4);
    out.write(message.bytes());
  }

  private static int int32(final byte[] body, final int at) {
    return (body[at] & 0xff) << 24 | (body[at + 1] & 0xff) << 16 | (body[at + 2] & 0xff) << 8 | body[at + 3] & 0xff;
  }

  private static int int16(final byte[] body, final int at) {
    return (body[at] & 0xff) << 8 | body[at + 1] & 0xff;
  }

  private static int terminator(final byte[] body, final int from) {
    int end = from;
    while (end < body.length && body[end] != 0)
      end++;
    return end;
  }

  private void parameter(final byte[] body) {
    final int nameEnd = terminator(body, 0);
    final int valueEnd = terminator(body, nameEnd + 1);
    parameters.put(new String(body, 0, nameEnd, StandardCharsets.UTF_8),
        new String(body, nameEnd + 1, valueEnd - nameEnd - 1, StandardCharsets.UTF_8));
  }

  private static void error(final byte[] body, final Reply reply) {
    int at = 0;
    while (at < body.length && body[at] != 0) {
      final char field = (char) body[at];
      final int end = terminator(body, at + 1);
      final String value = new String(body, at + 1, end - at - 1, StandardCharsets.UTF_8);
      if (field == 'C')
        reply.sqlState = value;
      else if (field == 'M')
        reply.message = value;
      at = end + 1;
    }
  }

  private static List<Column> columns(final byte[] body) {
    final int count = int16(body, 0);
    final List<Column> columns = new ArrayList<>(count);
    int at = 2;
    for (int i = 0; i < count; i++) {
      final int end = terminator(body, at);
      final String name = new String(body, at, end - at, StandardCharsets.UTF_8);
      // the table (4 bytes) and the column number (2), then the type (4), its length (2), its modifier (4) and the format (2)
      columns.add(new Column(name, int32(body, end + 7)));
      at = end + 19;
    }
    return columns;
  }

  private static String[] row(final byte[] body) {
    final String[] row = new String[int16(body, 0)];
    int at = 2;
    for (int i = 0; i < row.length; i++) {
      final int length = int32(body, at);
      at += 4;
      if (length >= 0) {
        row[i] = new String(body, at, length, StandardCharsets.UTF_8);
        at += length;
      }
    }
    return row;
  }

  /** The body of a message to the server. */
  private static final class Message {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    Message int8(final int value) {
      buffer.write(value);
      return this;
    }

    Message int16(final int value) {
      return int8(value >>> 8).int8(value);
    }

    Message int32(final int value) {
      return int16(value >>> 16).int16(value);
    }

    Message raw(final byte[] bytes) {
      buffer.write(bytes, 0, bytes.length);
      return this;
    }

    Message string(final String value) {
      return raw(value.getBytes(StandardCharsets.UTF_8)).int8(0);
    }

    int size() {
      return buffer.size();
    }

    byte[] bytes() {
      return buffer.toByteArray();
    }
  }

  @Override
  public void close() {
    try {
      send('X', new Message());
      out.flush();
    } catch (final IOException ignored) {
      // the server is gone already
    } finally {
      try {
        socket.close();
      } catch (final IOException ignored) {
        // nothing to release
      }
    }
  }
}
