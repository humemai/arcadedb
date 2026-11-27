import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.query.sql.executor.ResultSet;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Profiling harness of the pg14 write tasks t6 (single INSERT), t7 (UPDATE by key), t8 (DELETE by key), t9 (batch INSERT of 10,000 rows),
 * each call in its own transaction as the kit's run_write: begin, command, drain, commit. The statement texts are those of pg14lib.write_text.
 * usage: PgWrite <dataDir> <dbDir> <task t6..t9> <arm P|S> <warmCalls> <measuredCalls> [jfrOut]
 */
public class PgWrite {
  static final long NEW_ID_BASE = 1_000_000_000L;

  static String lit(final String s) {
    return "'" + s.replace("'", "''") + "'";
  }

  static String row(final long i) {
    return "(" + i + ", " + lit("memory " + i) + ", " + (20 + i % 50) + ", " + lit("city_" + (1000 + i % 1343)) + ", " + (1_300_000_000_000L + i) + ")";
  }

  static void drain(final Database db, final String lang, final String text) {
    db.begin();
    try (ResultSet rs = db.command(lang, text)) {
      while (rs.hasNext())
        rs.next();
    } catch (final RuntimeException e) {
      db.rollback();
      throw e;
    }
    db.commit();
  }

  public static void main(final String[] a) throws Exception {
    final Path data = Path.of(a[0]);
    final String task = a[2], arm = a[3];
    final int warm = Integer.parseInt(a[4]), calls = Integer.parseInt(a[5]);
    final String jfr = a.length > 6 ? a[6] : null;
    final String lang = arm.equals("P") ? "postgresql" : "sql";
    final String tbl = arm.equals("P") ? "memp" : "MemS";
    final DatabaseFactory f = new DatabaseFactory(a[1]);
    final Database db = f.open();
    final int total = warm + calls;
    final int per = task.equals("t9") ? 10_000 : 1;
    final List<String> persons = Files.readAllLines(data.resolve("persons.tsv"));
    final String[] texts = new String[total];
    for (int i = 0; i < total; i++) {
      final long id = NEW_ID_BASE + i;
      switch (task) {
      case "t6" -> texts[i] = "INSERT INTO " + tbl + " (id, name, age, city, created) VALUES " + row(id);
      case "t7" -> texts[i] = "UPDATE " + tbl + " SET age = " + (20 + i % 60) + " WHERE id = " + persons.get(i % 200).split("\t")[0];
      case "t8" -> texts[i] = "DELETE FROM " + tbl + " WHERE id = " + id;
      default -> {
        final StringBuilder sb = new StringBuilder("INSERT INTO " + tbl + " (id, name, age, city, created) VALUES ");
        for (int r = 0; r < per; r++)
          sb.append(r == 0 ? "" : ", ").append(row(2_000_000_000L + (long) i * 100_000 + r));
        texts[i] = sb.toString();
      }
      }
    }
    if (task.equals("t8"))      // the rows the deletes remove, put in by the other language's path, untimed
      for (int s = 0; s < total; s += 500) {
        final StringBuilder sb = new StringBuilder("INSERT INTO " + (arm.equals("P") ? "MemP" : "MemS") + " (id, name, age, city, created) VALUES ");
        for (int r = s; r < Math.min(total, s + 500); r++)
          sb.append(r == s ? "" : ", ").append(row(NEW_ID_BASE + r));
        drain(db, "sql", sb.toString());
      }
    final com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    final long tid = Thread.currentThread().threadId();
    for (int i = 0; i < warm; i++) {
      drain(db, lang, texts[i]);
      if (task.equals("t9"))
        drain(db, "sql", "DELETE FROM " + (arm.equals("P") ? "MemP" : "MemS") + " WHERE id >= " + (2_000_000_000L + (long) i * 100_000) + " AND id < "
            + (2_000_000_000L + (long) i * 100_000 + per));
    }
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
    final long b0 = mx.getThreadAllocatedBytes(tid);
    for (int i = 0; i < calls; i++) {
      final long t0 = System.nanoTime();
      drain(db, lang, texts[warm + i]);
      ns[i] = System.nanoTime() - t0;
      if (task.equals("t9"))      // untimed cleanup
        drain(db, "sql", "DELETE FROM " + (arm.equals("P") ? "MemP" : "MemS") + " WHERE id >= " + (2_000_000_000L + (long) (warm + i) * 100_000)
            + " AND id < " + (2_000_000_000L + (long) (warm + i) * 100_000 + per));
    }
    final long b1 = mx.getThreadAllocatedBytes(tid);
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
    System.out.printf("RESULT task=%s arm=%s lang=%s calls=%d mean_us=%.2f p50_us=%.2f p10_us=%.2f cpu_us=0 bytes_per_call=%d rows_per_call=0%n", task, arm, lang, calls,
        sum / calls / 1e3, sorted[calls / 2] / 1e3, sorted[calls / 10] / 1e3, (b1 - b0) / calls);
    db.close();
    f.close();
  }
}
