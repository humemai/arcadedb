import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.query.sql.executor.ResultSet;

import java.nio.file.Files;
import java.nio.file.Path;

/** Pre-flight of the pg13 plan gates: EXPLAIN of each statement of the file (name, tab, text) with the traversal rule on and off, as the kit
 * sets it (on the database configuration). usage: PgExplain <statements.tsv> <dbDir> <outDir>. */
public class PgExplain {
  public static void main(final String[] a) throws Exception {
    final DatabaseFactory f = new DatabaseFactory(a[1]);
    final Database db = f.open();
    for (final String line : Files.readAllLines(Path.of(a[0]))) {
      final String[] c = line.split("\t", 2);
      // on, off, then on again: the second reading with the rule on is after the other setting ran with the same text
      for (final String mode : new String[] { "on", "off", "on2" }) {
        ((DatabaseInternal) db).getConfiguration().setValue(GlobalConfiguration.POSTGRESQL_TRAVERSAL_RULE, Boolean.valueOf(!mode.equals("off")));
        final StringBuilder plan = new StringBuilder();
        try (ResultSet rs = db.query("postgresql", "EXPLAIN " + c[1])) {
          while (rs.hasNext())
            plan.append((String) rs.next().getProperty("QUERY PLAN")).append('\n');
        }
        Files.writeString(Path.of(a[2], c[0] + "." + mode + ".plan"), plan.toString());
      }
    }
    System.out.println("explained");
    db.close();
    f.close();
  }
}
