package io.kestra.webserver.migration;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script into individual executable statements for the migration runner.
 *
 * <p>
 * Handles the constructs that break a naive {@code ;} split for PostgreSQL DDL/DML: {@code ;}
 * inside single-quoted string literals is kept, PostgreSQL dollar-quoted blocks
 * ({@code $$...$$} and tagged {@code $tag$...$tag$}) are kept intact, and line
 * ({@code -- ...}) and block ({@code /* ... *&#47;}) comments are dropped. Statements are
 * returned without their terminating {@code ;}.
 *
 * <p>
 * Shared by the dsh migrations ({@link V2_0_29DshSchemaMigration} and any future SQL migration
 * in this module) so the parser is defined once rather than copied per migration.
 */
public final class SqlScriptSplitter {

    private SqlScriptSplitter() {
        // static utility
    }

    public static List<String> splitStatements(final String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDollar = false;
        String dollarTag = null;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (!inSingleQuote && !inDollar && c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (!inSingleQuote && !inDollar && c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (!inDollar && c == '\'') {
                inSingleQuote = !inSingleQuote;
                current.append(c);
                i++;
                continue;
            }
            if (!inSingleQuote && !inDollar && c == '$') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                    j++;
                }
                if (j < n && sql.charAt(j) == '$') {
                    inDollar = true;
                    dollarTag = sql.substring(i, j + 1);
                    current.append(dollarTag);
                    i = j + 1;
                    continue;
                }
            }
            if (inDollar && sql.startsWith(dollarTag, i)) {
                inDollar = false;
                current.append(dollarTag);
                i += dollarTag.length();
                continue;
            }
            if (!inSingleQuote && !inDollar && c == ';') {
                statements.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
    }
}
