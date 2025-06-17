package org.postgresql.benchmark.statement;

import org.postgresql.PGConnection;
import org.postgresql.test.TestUtil;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.postgresql.benchmark.profilers.FlightRecorderProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.sql.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@Fork(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.MINUTES)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
public class SelectBatch {

  @State(Scope.Benchmark)
  public static class SchemaState {
    @Setup(Level.Trial)
    public void init() throws SQLException {
      try (Connection conn = TestUtil.openDB();
           Statement st = conn.createStatement();
           PreparedStatement ins = conn.prepareStatement(
               "INSERT INTO bench_select (id,val) VALUES (?,?)")) {
        st.execute("DROP TABLE IF EXISTS bench_select");
        st.execute("CREATE TABLE bench_select (id INT PRIMARY KEY, val TEXT)");
        for (int i = 1; i <= 1000; i++) {
          ins.setInt(1, i);
          ins.setString(2, "строка_" + i);
          ins.addBatch();
        }
        ins.executeBatch();
      }
    }
  }

  @State(Scope.Thread)
  public static class SimpleState {
    Connection connection;
    String sql = "SELECT val FROM bench_select WHERE id = ?";

    @Setup(Level.Trial)
    public void openConnection(SchemaState schema) throws SQLException {
      connection = TestUtil.openDB();
      ((PGConnection) connection).setPrepareThreshold(0);
    }

    @TearDown(Level.Trial)
    public void closeConnection() throws SQLException {
      connection.close();
    }
  }

  @State(Scope.Thread)
  public static class CompositeState {
    Connection connection;
    String[] sqls;

    @Setup(Level.Trial)
    public void init(SchemaState schema) throws SQLException {
      connection = TestUtil.openDB();
      ((PGConnection) connection).setPrepareThreshold(1);
      sqls = new String[1000];
      String template = "SELECT val FROM bench_select WHERE id = ?";
      for (int len = 1; len <= 1000; len++) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < len; j++) {
          if (j > 0) sb.append(';');
          sb.append(template);
        }
        sqls[len - 1] = sb.toString();
      }
    }

    @TearDown(Level.Trial)
    public void closeConnection() throws SQLException {
      connection.close();
    }
  }

  @Benchmark
  public void benchSimple(SimpleState s, Blackhole bh) throws SQLException {
    try (PreparedStatement ps = s.connection.prepareStatement(s.sql)) {
      ps.setInt(1, 1);
      bh.consume(ps.execute());
      try (ResultSet rs = ps.getResultSet()) {
        while (rs.next()) bh.consume(rs.getString(1));
      }
    }
  }

  @Benchmark
  @OperationsPerInvocation(500500)
  public void benchComposite(CompositeState s, Blackhole bh) throws SQLException {
    for (int i = 0; i < 1000; i++) {
      try (PreparedStatement ps = s.connection.prepareStatement(s.sqls[i])) {
        for (int idx = 1; idx <= i + 1; idx++) {
          ps.setInt(idx, idx);
        }
        boolean hasMore = ps.execute();
        bh.consume(hasMore);
        while (hasMore) {
          try (ResultSet rs = ps.getResultSet()) {
            while (rs.next()) {
              bh.consume(rs.getString(1));
            }
          }
          hasMore = ps.getMoreResults();
          bh.consume(hasMore);
        }
      }
    }
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(SelectBatch.class.getSimpleName())
        .addProfiler(GCProfiler.class)
        .addProfiler(FlightRecorderProfiler.class)
        .build();
    new Runner(opt).run();
  }
}
