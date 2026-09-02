package io.kestra.webserver.migration;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class V2_0_29DshSchemaMigrationTest {

    @Test
    void splitStatementsSplitsOnSemicolon() {
        List<String> statements = V2_0_29DshSchemaMigration.splitStatements(
            "CREATE TABLE a (id INT);\nALTER TABLE a ADD COLUMN b TEXT;\n"
        );
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("CREATE TABLE a");
        assertThat(statements.get(1)).contains("ALTER TABLE a");
    }

    @Test
    void splitStatementsIgnoresSemicolonInsideSingleQuotes() {
        List<String> statements = V2_0_29DshSchemaMigration.splitStatements(
            "INSERT INTO t (v) VALUES ('a;b');\nSELECT 1;\n"
        );
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("'a;b'");
    }

    @Test
    void splitStatementsIgnoresDollarQuotedBlocks() {
        List<String> statements = V2_0_29DshSchemaMigration.splitStatements(
            "CREATE FUNCTION f() RETURNS INT AS $$ BEGIN RETURN 1; END; $$ LANGUAGE plpgsql;\nSELECT 2;\n"
        );
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("RETURN 1;");
        assertThat(statements.get(1)).contains("SELECT 2");
    }

    @Test
    void splitStatementsDropsLineAndBlockComments() {
        List<String> statements = V2_0_29DshSchemaMigration.splitStatements(
            "-- leading comment\nCREATE TABLE a (id INT); -- trailing;comment\n/* block ; comment */\nSELECT 1;\n"
        );
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).doesNotContain("--").doesNotContain("trailing");
        assertThat(statements.get(1)).doesNotContain("block");
    }

    @Test
    void splitStatementsHandlesTrailingStatementWithoutSemicolon() {
        List<String> statements = V2_0_29DshSchemaMigration.splitStatements(
            "CREATE TABLE a (id INT);\nUPDATE t SET x = 1 WHERE y IS NULL"
        );
        assertThat(statements).hasSize(2);
        assertThat(statements.get(1)).contains("UPDATE t");
    }

    @Test
    void splitStatementsOfRealResourceKeepsEveryDdlStatement() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String sql = new String(
            cl.getResourceAsStream("migrations/2.0.29-dsh-schema-postgres.sql").readAllBytes()
        );
        List<String> statements = V2_0_29DshSchemaMigration.splitStatements(sql);
        // CREATE dsh_session, 3x ALTER, 3x CREATE INDEX, UPDATE, CREATE dsh_approval,
        // 1x CREATE INDEX, CREATE dsh_metrics, 1x CREATE INDEX = 12 executable statements
        assertThat(statements).hasSize(12);
        assertThat(statements).anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS dsh_session"));
        assertThat(statements).anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS dsh_approval"));
        assertThat(statements).anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS dsh_metrics"));
        assertThat(statements).anyMatch(s -> s.contains("UPDATE dsh_session SET owner = user_id"));
        // no comment leaked into executable statements
        assertThat(statements).noneMatch(s -> s.contains("--"));
    }
}
