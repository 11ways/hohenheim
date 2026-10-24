package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.database.ManagedDatabase;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The header scan that refuses a dump creating or connecting to a database of its own reads only the header: it stops
 * at the first table or data, never mistakes a data row for a statement, and holds no unbounded line.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class DumpHeaderScanTest {

    private static final ManagedDatabase.Engine POSTGRES = ManagedDatabase.Engine.POSTGRES;
    private static final ManagedDatabase.Engine MYSQL = ManagedDatabase.Engine.MYSQL;

    @Test
    void theScanFindsAHeaderSwitchAndStopsAtTheFirstTableOrData() throws IOException {
        // 1. A pg_dump --create header names its CREATE DATABASE and its \connect.
        assertThat(scan(POSTGRES, "SET client_encoding = 'UTF8';\nCREATE DATABASE shop WITH TEMPLATE = template0;\n"))
            .as("step 1: CREATE DATABASE").isEqualTo("CREATE DATABASE shop WITH TEMPLATE = template0;");
        assertThat(scan(POSTGRES, "SET x = 1;\r\n\\connect shop\r\nCREATE TABLE a (x integer);\n"))
            .as("step 1: \\connect, CRLF lines too").isEqualTo("\\connect shop");

        // 2. Every table and data start ends the header, so a switch after it is data, never a statement.
        for (String start : new String[]{"CREATE TABLE a (x integer);", "CREATE UNLOGGED TABLE a (x integer);",
                "CREATE FOREIGN TABLE a (x integer) SERVER s;", "COPY public.a (x) FROM stdin;",
                "INSERT INTO a VALUES (1);", "-- Data for Name: a; Type: TABLE DATA; Schema: public; Owner: app"}) {
            assertThat(scan(POSTGRES, "SET x = 1;\n" + start + "\n\\connect other\nCREATE DATABASE other;\n"))
                .as("step 2: '%s' ends the header", start).isNull();
        }

        // 3. MySQL's USE counts only as a statement on a line of its own, never inside a data row.
        assertThat(scan(MYSQL, "-- MySQL dump\nUSE `shop`;\nCREATE TABLE a (x int);\n"))
            .as("step 3: a USE statement").isEqualTo("USE `shop`;");
        assertThat(scan(MYSQL, "-- MySQL dump\n  USE `shop`;\nUSE shop and more\n"))
            .as("step 3: an indented or unquoted USE line is no statement").isNull();
        for (String start : new String[]{"CREATE TABLE a (x int);", "LOCK TABLES `a` WRITE;",
                "INSERT INTO `a` VALUES (1);", "-- Dumping data for table `a`"}) {
            assertThat(scan(MYSQL, "-- MySQL dump\n" + start + "\nUSE `other`;\nCREATE DATABASE other;\n"))
                .as("step 3: '%s' ends the header", start).isNull();
        }
        assertThat(scan(MYSQL, "-- MySQL dump\nCREATE DATABASE /*!32312 IF NOT EXISTS*/ `shop`;\n"))
            .as("step 3: CREATE DATABASE").startsWith("CREATE DATABASE ");

        // 4. A header of 17 MiB with no newline at all is read in chunks and given up at the cap: the switch past it
        //    is never seen, and the scan answers instead of holding the whole line.
        Path huge = Files.createTempFile("header-scan", ".sql");
        try {
            try (Writer out = Files.newBufferedWriter(huge, StandardCharsets.ISO_8859_1)) {
                char[] filler = new char[1 << 20];
                Arrays.fill(filler, 'x');
                for (int i = 0; i < 17; i++) {
                    out.write(filler);
                }
                out.write("\nCREATE DATABASE late;\n");
            }
            assertThat(POSTGRES.databaseSwitchIn(huge)).as("step 4: nothing past the cap is read").isNull();
        } finally {
            Files.deleteIfExists(huge);
        }
    }

    private static String scan(ManagedDatabase.Engine engine, String dump) throws IOException {
        Path file = Files.createTempFile("header-scan", ".sql");
        try {
            Files.writeString(file, dump, StandardCharsets.ISO_8859_1);
            return engine.databaseSwitchIn(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
