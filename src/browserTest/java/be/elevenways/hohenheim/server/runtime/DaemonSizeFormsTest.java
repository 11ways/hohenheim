package be.elevenways.hohenheim.server.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A size the Incus daemon reports reads in every form the daemon accepts, whoever created the volume: what Hohenheim
 * writes ("20GiB"), raw bytes, and the binary and decimal unit forms an operator or another tool may have used.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class DaemonSizeFormsTest {

    @Test
    void everySizeFormTheDaemonAcceptsReadsAsWholeGigabytesRoundedUp() {
        // 1. What Hohenheim writes, and raw bytes, read back exactly.
        assertThat(IncusDefinitions.parseSizeGb("20GiB")).as("step 1: Hohenheim's own form").isEqualTo(20);
        assertThat(IncusDefinitions.parseSizeGb(String.valueOf(5L << 30))).as("step 1: raw bytes").isEqualTo(5);
        assertThat(IncusDefinitions.parseSizeGb(5L << 30)).as("step 1: a number").isEqualTo(5);

        // 2. THE ASSERTION: a volume created outside Hohenheim, in a binary unit, has a size.
        assertThat(IncusDefinitions.parseSizeGb("1TiB")).as("step 2: tebibytes").isEqualTo(1024);
        assertThat(IncusDefinitions.parseSizeGb("2048MiB")).as("step 2: mebibytes").isEqualTo(2);
        assertThat(IncusDefinitions.parseSizeGb("1048576KiB")).as("step 2: kibibytes").isEqualTo(1);
        assertThat(IncusDefinitions.parseSizeGb("1073741824B")).as("step 2: bytes with their unit").isEqualTo(1);

        // 3. A decimal unit counts powers of 1000, so "10GB" is a little over 9 GiB: rounded up, a claim made from
        //    it always holds the volume, and a volume under one gigabyte still has a size.
        assertThat(IncusDefinitions.parseSizeGb("10GB")).as("step 3: decimal gigabytes").isEqualTo(10);
        assertThat(IncusDefinitions.parseSizeGb("1TB")).as("step 3: decimal terabytes").isEqualTo(932);
        assertThat(IncusDefinitions.parseSizeGb("500MB")).as("step 3: decimal megabytes").isEqualTo(1);
        assertThat(IncusDefinitions.parseSizeGb("500MiB")).as("step 3: under a gibibyte").isEqualTo(1);
        assertThat(IncusDefinitions.parseSizeGb("1000000kB")).as("step 3: the daemon's own kB").isEqualTo(1);
        assertThat(IncusDefinitions.parseSizeGb(" 8 GiB ")).as("step 3: spacing is read past").isEqualTo(8);

        // 4. What is no size the daemon accepts reads as none, never as a guess.
        assertThat(IncusDefinitions.parseSizeGb(null)).as("step 4: nothing").isNull();
        assertThat(IncusDefinitions.parseSizeGb("")).as("step 4: blank").isNull();
        assertThat(IncusDefinitions.parseSizeGb("lots")).as("step 4: words").isNull();
        assertThat(IncusDefinitions.parseSizeGb("1.5GiB")).as("step 4: a fraction, which the daemon refuses").isNull();
        assertThat(IncusDefinitions.parseSizeGb("10XB")).as("step 4: an unknown unit").isNull();
        assertThat(IncusDefinitions.parseSizeGb("-1GiB")).as("step 4: a negative size").isNull();
    }
}
