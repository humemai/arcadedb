import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.database.RID;
import com.arcadedb.query.sql.executor.ResultSet;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Profiling harness of the pg14 core tasks t1..t5 on the embedded engine, with the kit's schema, data, parameters and statement texts.
 * usage: PgPerf load <dataDir> <dbDir>
 *        PgPerf run <dataDir> <dbDir> <task> <arm P|S|C> <warmCalls> <measuredCalls> [jfrOut]
 * Statement texts are those of pg14lib.read_text (the kit), the call is db.query(lang, text) drained row by row, as the kit's run_read.
 */
public class PgPerf {
  public static void main(final String[] a) throws Exception {
    if (a[0].equals("load"))
      load(Path.of(a[1]), a[2]);
    else
      run(Path.of(a[1]), a[2], a[3], a[4], Integer.parseInt(a[5]), Integer.parseInt(a[6]), a.length > 7 ? a[7] : null);
  }

  static void load(final Path data, final String dbDir) throws IOException {
    final DatabaseFactory f = new DatabaseFactory(dbDir);
    if (f.exists())
      f.open().drop();
    final Database db = f.create();
    db.command("sql", "CREATE VERTEX TYPE Person");
    for (final String p : new String[] { "id LONG", "name STRING", "age INTEGER", "city STRING", "created LONG" })
      db.command("sql", "CREATE PROPERTY Person." + p);
    db.command("sql", "CREATE INDEX ON Person (id) UNIQUE");
    db.command("sql", "CREATE EDGE TYPE KNOWS CUSTOM source_table = 'Person', target_table = 'Person'");
    db.command("sql", "CREATE PROPERTY KNOWS.since INTEGER");
    final Map<Long, RID> rid = new HashMap<>();
    final List<String> persons = Files.readAllLines(data.resolve("persons.tsv"));
    for (int s = 0; s < persons.size(); s += 5000) {
      db.begin();
      for (final String line : persons.subList(s, Math.min(persons.size(), s + 5000))) {
        final String[] c = line.split("\t");
        final MutableVertex v = db.newVertex("Person").set("id", Long.parseLong(c[0]), "name", c[1], "age", Integer.parseInt(c[2]), "city", c[3],
            "created", Long.parseLong(c[4]));
        v.save();
        rid.put(Long.parseLong(c[0]), v.getIdentity());
      }
      db.commit();
    }
    final List<String> edges = Files.readAllLines(data.resolve("edges.tsv"));
    for (int s = 0; s < edges.size(); s += 20000) {
      db.begin();
      for (final String line : edges.subList(s, Math.min(edges.size(), s + 20000))) {
        final String[] c = line.split("\t");
        db.lookupByRID(rid.get(Long.parseLong(c[0])), true).asVertex().modify().newEdge("KNOWS", rid.get(Long.parseLong(c[1])), "since",
            Integer.parseInt(c[2]));
      }
      db.commit();
    }
    final ResultSet r = db.query("sql", "SELECT count(*) AS n FROM Person");
    final ResultSet k = db.query("sql", "SELECT count(*) AS n FROM KNOWS");
    System.out.println("loaded persons=" + r.next().getProperty("n") + " knows=" + k.next().getProperty("n"));
    db.close();
    f.close();
  }

  static String text(final String task, final String arm, final String x) {
    final String person = arm.equals("P") ? "person" : "Person";
    final String knows = arm.equals("P") ? "knows" : "KNOWS";
    switch (task) {
    case "t1":
      return "SELECT id, name, age, city, created FROM " + person + " WHERE id = " + x;
    case "t2":
      return "SELECT id, name, created FROM " + person + " WHERE city = '" + x + "' ORDER BY created DESC, id DESC LIMIT 10";
    case "t3":
      return "SELECT age, count(*) AS n, sum(created) AS created_sum FROM " + person + " GROUP BY age";
    case "t4":
      return "SELECT id, name, age FROM " + person + " WHERE city = '" + x + "'";
    case "t5":
      if (arm.equals("P"))
        return "SELECT q.id AS qid, q.name AS name, k.since AS since FROM person p JOIN knows k ON k.source_id = p.rid JOIN person q ON k.target_id = q.rid WHERE p.id = "
            + x;
      if (arm.equals("C"))
        return "MATCH (p:Person)-[k:KNOWS]->(q:Person) WHERE p.id = " + x + " RETURN q.id AS qid, q.name AS name, k.since AS since";
      return "SELECT in.id AS qid, in.name AS name, since FROM (SELECT expand(outE('KNOWS')) FROM Person WHERE id = " + x + ")";
    }
    throw new IllegalArgumentException(task);
  }

  static List<String> params(final Path data, final String task) throws IOException {
    final String j = Files.readString(data.resolve("params.json"));
    final int i = j.indexOf("\"" + task + "\"");
    if (i < 0)
      return List.of("");
    final int s = j.indexOf('[', i), e = j.indexOf(']', s);
    final List<String> out = new ArrayList<>();
    for (final String p : j.substring(s + 1, e).split(",\\s*"))
      out.add(p.trim().replaceAll("^\"|\"$", ""));
    return out;
  }

  static long drain(final Database db, final String lang, final String text) {
    long n = 0;
    try (ResultSet rs = db.query(lang, text)) {
      while (rs.hasNext()) {
        rs.next();
        n++;
      }
    }
    return n;
  }

  static void run(final Path data, final String dbDir, final String task, final String arm, final int warm, final int calls, final String jfr)
      throws Exception {
    final String lang = arm.equals("P") ? "postgresql" : arm.equals("C") ? "opencypher" : "sql";
    final List<String> ps = params(data, task);
    final List<String> texts = new ArrayList<>();
    for (final String x : task.equals("t3") ? List.of("") : ps)
      texts.add(text(task, arm, x));
    final DatabaseFactory f = new DatabaseFactory(dbDir);
    final Database db = f.open();
    final com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    final long tid = Thread.currentThread().threadId();
    long rows = 0;
    for (int i = 0; i < warm; i++)
      rows += drain(db, lang, texts.get(i % texts.size()));
    System.gc();
    Recording rec = null;
    if (jfr != null) {
      rec = new Recording(Configuration.getConfiguration("profile"));
      rec.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2)).withStackTrace();
      rec.enable("jdk.ObjectAllocationSample").with("throttle", "20000/s").withStackTrace();
      rec.setToDisk(true);
      rec.start();
    }
    final long[] ns = new long[calls];
    final long b0 = mx.getThreadAllocatedBytes(tid), c0 = mx.getThreadCpuTime(tid);
    for (int i = 0; i < calls; i++) {
      final String t = texts.get(i % texts.size());
      final long t0 = System.nanoTime();
      rows += drain(db, lang, t);
      ns[i] = System.nanoTime() - t0;
    }
    final long b1 = mx.getThreadAllocatedBytes(tid), c1 = mx.getThreadCpuTime(tid);
    if (rec != null) {
      rec.stop();
      rec.dump(Path.of(jfr));
      rec.close();
    }
    final long[] sorted = ns.clone();
    Arrays.sort(sorted);
    double sum = 0;
    for (final long v : ns)
      sum += v;
    System.out.printf("RESULT task=%s arm=%s lang=%s calls=%d mean_us=%.2f p50_us=%.2f p10_us=%.2f cpu_us=%.2f bytes_per_call=%d rows_per_call=%.1f%n", task, arm,
        lang, calls, sum / calls / 1e3, sorted[calls / 2] / 1e3, sorted[calls / 10] / 1e3, (c1 - c0) / (double) calls / 1e3, (b1 - b0) / calls,
        rows / (double) (warm + calls));
    db.close();
    f.close();
  }
}
