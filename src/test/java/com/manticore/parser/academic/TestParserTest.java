package com.manticore.parser.academic;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.manticore.parser.academic.*;

public class TestParserTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "AT (TIMESTAMP => 'Wed, 26 Jun 2024 09:20:00 -0700')",
            "AT (STATEMENT => '8e5d0ca9-005e-44e6-b858-a8f5b37c5726')",
            "BEFORE (STATEMENT => '8e5d0ca9-005e-44e6-b858-a8f5b37c5726')",
            "@ 20240618093000000",
            "@V 5",
            " SYSTEM_TIMESTAMP AS OF '2024-06-01T00:00:00'",
            " SYSTEM_VERSION AS OF 3",
    })
    void simpleParserTest(String input) throws ParseException {
        String output = new TestParser(input).parseTimeTravel();

        Assertions.assertEquals(input, output);
    }
}
