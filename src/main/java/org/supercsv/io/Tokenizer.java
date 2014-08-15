/*
 * Copyright 2007 Kasper B. Graversen
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.supercsv.io;

import org.supercsv.comment.CommentMatcher;
import org.supercsv.exception.SuperCsvException;
import org.supercsv.prefs.CsvPreference;

import java.io.IOException;
import java.io.Reader;
import java.util.List;

/**
 * Reads the CSV file, line by line. If you want the line-reading functionality of this class, but want to define your
 * own implementation of {@link #readColumns(java.util.List)}, then consider writing your own Tokenizer by extending
 * AbstractTokenizer.
 *
 * @author Kasper B. Graversen
 * @author James Bassett
 */
public class Tokenizer extends AbstractTokenizer {


    private final StringBuilder currentColumn = new StringBuilder();

    /* the raw, untokenized CSV row (may span multiple lines) */
    private final StringBuilder currentRow = new StringBuilder();

    private final int quoteChar;

    private final int escapeChar;

    private final int delimeterChar;

    private final boolean surroundingSpacesNeedQuotes;

    private final CommentMatcher commentMatcher;

    private static final char NEWLINE = '\n';
    private static final char SPACE = ' ';
    private static final char TAB = '\t';
    private static final char BSPACE = '\b';
    private static final char CR = '\r';
    private static final char SQUOTE = '\'';
    private static final char DQUOTE = '\"';
    private static final char BKSLSH = '\\';

    /**
     * Enumeration of tokenizer states. QUOTE_MODE is activated between quotes.
     */
    private enum TokenizerState {
        NORMAL, QUOTE_MODE, ESCAPE_MODE;
    }

    /**
     * Constructs a new <tt>Tokenizer</tt>, which reads the CSV file, line by line.
     *
     * @param reader      the reader
     * @param preferences the CSV preferences
     * @throws NullPointerException if reader or preferences is null
     */
    public Tokenizer(final Reader reader, final CsvPreference preferences) {
        super(reader, preferences);
        this.quoteChar = preferences.getQuoteChar();
        this.escapeChar = preferences.getEscapeChar();
        this.delimeterChar = preferences.getDelimiterChar();
        this.surroundingSpacesNeedQuotes = preferences.isSurroundingSpacesNeedQuotes();
        this.commentMatcher = preferences.getCommentMatcher();
    }

    /**
     * {@inheritDoc}
     */
    public boolean readColumns(final List<String> columns) throws IOException {

        State state = new State();

        if (columns == null) {
            throw new NullPointerException("columns should not be null");
        }

        // clear the reusable List and StringBuilders
        columns.clear();
        currentColumn.setLength(0);
        currentRow.setLength(0);

        // keep reading lines until data is found
        state.line = "";
        do {
            state.line = readLine();
            if (state.line == null) {
                return false; // EOF
            }
        }
        while (state.line.length() == 0 || (commentMatcher != null && commentMatcher.isComment(state.line)));

        // update the untokenized CSV row
        currentRow.append(state.line);

        // add a newline to determine end of line (making parsing easier)
        state.line += NEWLINE;

        // process each character in the line, catering for surrounding quotes (QUOTE_MODE)
        while (true) {

            final char c = state.line.charAt(state.charIndex);

            if (TokenizerState.NORMAL.equals(state.state)) {
                if (processNormalMode(c, state, columns))
                    return true;

            } else if (TokenizerState.QUOTE_MODE.equals(state.state)) {
                processQuoteMode(c, state);

            } else {
                // ESCAPE_MODE
                processEscapeMode(c, state);
            }
            state.charIndex++; // read next char of the line
        }
    }

    /**
     * Processes the input character using the tokenizer state in NORMAL mode.
     *
     * @param c       the current character
     * @param state   current state of the parsing
     * @param columns output columns
     */

    private Boolean processNormalMode(char c, State state, List<String> columns) {
        if (c == delimeterChar) {
                    /*
                     * Delimiter. Save the column (trim trailing space if required) then continue to next character.
					 */
            if (!surroundingSpacesNeedQuotes) {
                appendSpaces(currentColumn, state.potentialSpaces);
            }
            columns.add(currentColumn.length() > 0 ? currentColumn.toString() : null); // "" -> null
            state.potentialSpaces = 0;
            currentColumn.setLength(0);

        } else if (c == SPACE) {
                    /*
					 * Space. Remember it, then continue to next character.
					 */
            state.potentialSpaces++;

        } else if (c == NEWLINE) {
					/*
					 * Newline. Add any required spaces (if surrounding spaces don't need quotes) and return (we've read
					 * a line!).
					 */
            if (!surroundingSpacesNeedQuotes) {
                appendSpaces(currentColumn, state.potentialSpaces);
            }
            columns.add(currentColumn.length() > 0 ? currentColumn.toString() : null); // "" -> null
            return true;

        } else if (c == quoteChar) {
					/*
					 * A single quote ("). Update to QUOTESCOPE (but don't save quote), then continue to next character.
					 */
            state.state = TokenizerState.QUOTE_MODE;
            state.quoteScopeStartingLine = getLineNumber();

            // cater for spaces before a quoted section (be lenient!)
            if (!surroundingSpacesNeedQuotes || currentColumn.length() > 0) {
                appendSpaces(currentColumn, state.potentialSpaces);
            }
            state.potentialSpaces = 0;

        } else {
					/*
					 * Just a normal character. Add any required spaces (but trim any leading spaces if surrounding
					 * spaces need quotes), add the character, then continue to next character.
					 */
            if (!surroundingSpacesNeedQuotes || currentColumn.length() > 0) {
                appendSpaces(currentColumn, state.potentialSpaces);
            }

            state.potentialSpaces = 0;
            currentColumn.append(c);
        }
        return false;
    }

    /**
     * Processes the input character using the tokenizer state in QUOTE mode.
     *
     * @param c       the current character
     * @param state   current state of the parsing
     */

    private void processQuoteMode(char c, State state) throws IOException {
        	/*
				 * QUOTE_MODE (within quotes).
				 */

        if (c == NEWLINE) {

					/*
					 * Newline. Doesn't count as newline while in QUOTESCOPE. Add the newline char, reset the charIndex
					 * (will update to 0 for next iteration), read in the next line, then then continue to next
					 * character. For a large file with an unterminated quoted section (no trailing quote), this could
					 * cause memory issues as it will keep reading lines looking for the trailing quote. Maybe there
					 * should be a configurable limit on max lines to read in quoted mode?
					 */
            currentColumn.append(NEWLINE);
            currentRow.append(NEWLINE); // specific line terminator lost, \n will have to suffice

            state.charIndex = -1;
            state.line = readLine();
            if (state.line == null) {
                throw new SuperCsvException(
                        String
                                .format(
                                        "unexpected end of file while reading quoted column beginning on line %d and ending on line %d",
                                        state.quoteScopeStartingLine, getLineNumber()));
            }

            currentRow.append(state.line); // update untokenized CSV row
            state.line += NEWLINE; // add newline to simplify parsing

        } else if (c == quoteChar) {

            if (state.line.charAt(state.charIndex + 1) == quoteChar) {
						/*
						 * An escaped quote (""). Add a single quote, then move the cursor so the next iteration of the
						 * loop will read the character following the escaped quote.
						 */
                currentColumn.append(c);
                state.charIndex++;

            } else {
						/*
						 * A single quote ("). Update to NORMAL (but don't save quote), then continue to next character.
						 */
                state.state = TokenizerState.NORMAL;
                state.quoteScopeStartingLine = -1; // reset ready for next multi-line cell
            }
        } else if (c == escapeChar) {

						/*
						 * An escaped quote (\"). Add a single quote, then move the cursor so the next iteration of the
						 * loop will read the character following the escaped quote.
						 */
            state.state = TokenizerState.ESCAPE_MODE;

        } else {
					/*
					 * Just a normal character, delimiter (they don't count in QUOTESCOPE) or space. Add the character,
					 * then continue to next character.
					 */
            currentColumn.append(c);
        }
    }

    /**
     * Processes the input character using the tokenizer state in ESCAPE mode.
     *
     * @param c       the current character
     * @param state   current state of the parsing
     */

    private void processEscapeMode(char c, State state) throws IOException {
        	/*
				 * ESCAPE_MODE (at the position after an escape character).
			 */
        state.state = TokenizerState.QUOTE_MODE;

        if (c == escapeChar || c == quoteChar ) {
            currentColumn.append(c);
        } else if (escapeChar == BKSLSH) {
            switch (c) {
                case 't':
                    currentColumn.append(TAB);
                    break;
                case 'b':
                    currentColumn.append(BSPACE);
                    break;
                case 'n':
                    currentColumn.append(NEWLINE);
                    break;
                case 'r':
                    currentColumn.append(CR);
                    break;
                case '\'':
                case '\"':
                case '\\':
                    currentColumn.append(c);
                    break;
            }
        }else{
            throw new SuperCsvException(
                    String
                            .format(
                                    "unexpected character '%c' after the escape character '%c' while reading quoted column beginning on line %d and ending on line %d",
                                    c, escapeChar, state.quoteScopeStartingLine, getLineNumber()));
        }
    }

    /**
     * Appends the required number of spaces to the StringBuilder.
     *
     * @param sb     the StringBuilder
     * @param spaces the required number of spaces to append
     */
    private static void appendSpaces(final StringBuilder sb, final int spaces) {
        for (int i = 0; i < spaces; i++) {
            sb.append(SPACE);
        }
    }

    /**
     * {@inheritDoc}
     */
    public String getUntokenizedRow() {
        return currentRow.toString();
    }

    class State {

        TokenizerState state = TokenizerState.NORMAL;

        int quoteScopeStartingLine = -1; // the line number where a potential multi-line cell starts

        int escapeScopeStartingLine = -1; // the line number where a potential multi-line cell starts

        int potentialSpaces = 0; // keep track of spaces (so leading/trailing space can be removed if required)

        int charIndex = 0;

        String line;

    }
}
