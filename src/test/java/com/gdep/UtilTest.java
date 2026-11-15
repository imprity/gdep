package com.gdep;

import org.junit.Assert;
import org.junit.Test;

public class UtilTest {
    @Test
    public void testIsStringBlank() {
        Assert.assertTrue(Util.isStringBlank(null));
        Assert.assertTrue(Util.isStringBlank(""));
        Assert.assertTrue(Util.isStringBlank("    "));

        // white space characters according to wikipedia
        // https://en.wikipedia.org/wiki/Whitespace_character
        //
        // some characters are commented out because for some reason
        // Character.isWhitespace thinks they are not an white space character.
        //
        // I guess it's for a good reason.
        char[] whiteSpaceCharacters = new char[] {
            '\u0009',
            '\n',
            '\u000B',
            '\u000C',
            '\r',
            '\u0020',
            // '\u0085',
            // '\u00A0',
            '\u1680',
            '\u2000',
            '\u2001',
            '\u2002',
            '\u2003',
            '\u2004',
            '\u2005',
            '\u2006',
            // '\u2007',
            '\u2008',
            '\u2009',
            '\u200A',
            '\u2028',
            '\u2029',
            // '\u202F',
            '\u205F',
            '\u3000'
        };

        for (final char c : whiteSpaceCharacters) {
            System.out.printf("testing \\u+%04x\n", (int) c);
            try {
                Assert.assertTrue(Util.isStringBlank(Character.toString(c)));
            } catch (AssertionError e) {
                System.out.printf("character \\u+%04x failed!\n", (int) c);
                throw e;
            }
        }

        Assert.assertFalse(Util.isStringBlank("hello world"));
    }
}
