/*
 * Copyright (c) 2011 Matthew Francis
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.itadaki.bzip2;

import java.io.IOException;
import java.util.Arrays;


/**
 * An encoder for the BZip2 Huffman encoding stage
 */
class BZip2HuffmanStageEncoder {

	/**
	 * Used in initial Huffman table generation
	 */
	private static final int HUFFMAN_HIGH_SYMBOL_COST = 15;

	/**
	 * The BitOutputStream from which Huffman codes are written
	 */
	private final BitOutputStream bitOutputStream;

	/**
	 * The Burrows-Wheeler transformed block
	 */
	private final int[] bwtBlock;

	/**
	 * Actual length of the BWT data
	 */
	private int bwtLength;

	/**
	 * At each position, {@code true} if the byte value with that index is present within the block,
	 * otherwise {@code false} 
	 */
	private final boolean[] bwtValuesInUse;

	/**
	 * The output of the Move To Front stage
	 */
	private final char[] mtfBlock;

	/**
	 * The actual number of values contained in the {@link mtf} array
	 */
	private int mtfLength;

	/**
	 * The Huffman alphabet size
	 */
	private int huffmanAlphabetSize;

	/**
	 * The global frequencies of values within the {@link mtf} array
	 */
	private final int[] mtfSymbolFrequencies = new int[BZip2Constants.HUFFMAN_MAXIMUM_ALPHABET_SIZE];

	/**
	 * The lengths of the Canonical Huffman codes for each table
	 */
	private final int[][] huffmanCodeLengths = new int[BZip2Constants.HUFFMAN_MAXIMUM_TABLES][BZip2Constants.HUFFMAN_MAXIMUM_ALPHABET_SIZE];

	/**
	 * A table of Canonical Huffman codes for each table. The value at each position is ((code length << 24) | code)
	 */
	private final int[][] huffmanMergedCodeSymbols = new int[BZip2Constants.HUFFMAN_MAXIMUM_TABLES][BZip2Constants.HUFFMAN_MAXIMUM_ALPHABET_SIZE];

	/**
	 * The selectors for each segment
	 */
	private final byte[] selectors = new byte[BZip2Constants.HUFFMAN_MAXIMUM_SELECTORS];


	/**
	 * Selects an appropriate table count for a given MTF length
	 * @param mtfLength The length to select a table count for
	 * @return The selected table count
	 */
	private static int selectTableCount (final int mtfLength) {

		if (mtfLength >= 2400) return 6;
		if (mtfLength >= 1200) return 5;
		if (mtfLength >= 600) return 4;
		if (mtfLength >= 200) return 3;
		return 2;

	}


	/**
	 * Performs the Move To Front transform and Run Length Encoding[1] stages
	 */
	private void moveToFrontAndRunLengthEncode() {

		final int bwtLength = this.bwtLength;
		final boolean[] bwtValuesInUse = this.bwtValuesInUse;
		final int[] bwtBlock = this.bwtBlock;
		final char[] mtfBlock = this.mtfBlock;
		final int[] mtfSymbolFrequencies = this.mtfSymbolFrequencies;
		final byte[] huffmanSymbolMap = new byte[256];
		final MoveToFront symbolMTF = new MoveToFront();

		int totalUniqueValues = 0;
		for (int i = 0; i < 256; i++) {
			if (bwtValuesInUse[i]) {
				huffmanSymbolMap[i] = (byte) totalUniqueValues++;
			}
		}

		final int endOfBlockSymbol = totalUniqueValues + 1;

		int mtfIndex = 0;
		int repeatCount = 0;
		int totalRunAs = 0;
		int totalRunBs = 0;

		for (int i = 0; i < bwtLength; i++) {

			// Move To Front
			int mtfPosition = symbolMTF.valueToFront (huffmanSymbolMap[bwtBlock[i] & 0xff]);

			// Run Length Encode
			if (mtfPosition == 0) {
				repeatCount++;
			} else {
				if (repeatCount > 0) {
					repeatCount--;
					while (true) {
						if ((repeatCount & 1) == 0) {
							mtfBlock[mtfIndex++] = BZip2Constants.HUFFMAN_SYMBOL_RUNA;
							totalRunAs++;
						} else {
							mtfBlock[mtfIndex++] = BZip2Constants.HUFFMAN_SYMBOL_RUNB;
							totalRunBs++;
						}

						if (repeatCount < 2) {
							break;
						}
						repeatCount = (repeatCount - 2) >>> 1;
					}
					repeatCount = 0;
				}

				mtfBlock[mtfIndex++] = (char) (mtfPosition + 1);
				mtfSymbolFrequencies[mtfPosition + 1]++;
			}
		}

		if (repeatCount > 0) {
			repeatCount--;
			while (true) {
				if ((repeatCount & 1) == 0) {
					mtfBlock[mtfIndex++] = BZip2Constants.HUFFMAN_SYMBOL_RUNA;
					totalRunAs++;
				} else {
					mtfBlock[mtfIndex++] = BZip2Constants.HUFFMAN_SYMBOL_RUNB;
					totalRunBs++;
				}

				if (repeatCount < 2) {
					break;
				}
				repeatCount = (repeatCount - 2) >>> 1;
			}
		}

		mtfBlock[mtfIndex] = (char) endOfBlockSymbol;
		mtfSymbolFrequencies[endOfBlockSymbol]++;
		mtfSymbolFrequencies[BZip2Constants.HUFFMAN_SYMBOL_RUNA] += totalRunAs;
		mtfSymbolFrequencies[BZip2Constants.HUFFMAN_SYMBOL_RUNB] += totalRunBs;

		this.mtfLength = mtfIndex + 1;
		this.huffmanAlphabetSize = totalUniqueValues + 2;

	}


	/**
	 * Generate initial Huffman code length tables, giving each table a different low cost section
	 * of the alphabet that is roughly equal in overall cumulative frequency. Note that the initial
	 * tables are invalid for actual Huffman code generation, and only serve as the seed for later
	 * iterative optimisation in {@link #optimiseSelectorsAndHuffmanTables(int)}.
	 * @param totalTables The total number of Huffman tables
	 */
	private void generateInitialHuffmanCodeLengths (final int totalTables) {

		final int[][] huffmanCodeLengths = this.huffmanCodeLengths;
		final int[] mtfSymbolFrequencies = this.mtfSymbolFrequencies;
		final int huffmanAlphabetSize = this.huffmanAlphabetSize;

		int remainingLength = this.mtfLength;
		int lowCostEnd = -1;

		for (int i = 0; i < totalTables; i++) {

			final int targetCumulativeFrequency = remainingLength / (totalTables - i);
			final int lowCostStart = lowCostEnd + 1;
			int actualCumulativeFrequency = 0;

			while ((actualCumulativeFrequency < targetCumulativeFrequency) && (lowCostEnd < (huffmanAlphabetSize - 1))) {
				actualCumulativeFrequency += mtfSymbolFrequencies[++lowCostEnd];
			}

			if ((lowCostEnd > lowCostStart) && (i != 0) && (i != (totalTables - 1)) && (((totalTables - i) & 1) == 0)) {
				actualCumulativeFrequency -= mtfSymbolFrequencies[lowCostEnd--];
			}

			final int[] tableCodeLengths = huffmanCodeLengths[i];
			for (int j = 0; j < huffmanAlphabetSize; j++) {
				if ((j < lowCostStart) || (j > lowCostEnd)) {
					tableCodeLengths[j] = HUFFMAN_HIGH_SYMBOL_COST;
				}
			}

			remainingLength -= actualCumulativeFrequency;

		}

	}


	/**
	 * Iteratively co-optimise the selector list and the alternative Huffman table code lengths
	 * @param totalTables The total number of tables
	 * @return The total number of selectors
	 */
	private int optimiseSelectorsAndHuffmanTables (final int totalTables) {

		final int MAXIMUM_ITERATIONS = 4;

		final char[] mtf = this.mtfBlock;
		final byte[] selectors = this.selectors;
		final int[][] huffmanCodeLengths = this.huffmanCodeLengths;
		final int mtfLength = this.mtfLength;
		final int huffmanAlphabetSize = this.huffmanAlphabetSize;

		final int[] sortedFrequencyMap = new int[huffmanAlphabetSize];
		final int[] sortedFrequencies = new int[huffmanAlphabetSize];

		int selectorIndex = 0;

		for (int iteration = MAXIMUM_ITERATIONS - 1; iteration >= 0; iteration--) {

			int[][] tableFrequencies = new int[BZip2Constants.HUFFMAN_MAXIMUM_TABLES][huffmanAlphabetSize];
			selectorIndex = 0;

			// Find the best table for each group based on the current Huffman code lengths
			for (int groupStart = 0; groupStart < mtfLength;) {

				final int groupEnd = Math.min (groupStart + BZip2Constants.HUFFMAN_GROUP_RUN_LENGTH - 1, mtfLength - 1);

				short[] cost = new short[BZip2Constants.HUFFMAN_MAXIMUM_TABLES];
				for (int i = groupStart; i <= groupEnd; i++) {
					final int value = mtf[i];
					for (int j = 0; j < totalTables; j++) {
						cost[j] += huffmanCodeLengths[j][value];
					}
				}

				int bestTable = 0;
				int bestCost = cost[0];
				for (int i = 1 ; i < totalTables; i++) {
					final int tableCost = cost[i];
					if (tableCost < bestCost) {
						bestCost = tableCost;
						bestTable = i;
					}
				}

				final int[] bestGroupFrequencies = tableFrequencies[bestTable];
				for (int i = groupStart; i <= groupEnd; i++) {
					bestGroupFrequencies[mtf[i]]++;
				}

				if (iteration == 0) {
					selectors[selectorIndex++] = (byte) bestTable;
				}

				groupStart = groupEnd + 1;

			}

			// Calculate new Huffman code lengths based on the table frequencies accumulated in this iteration
			for (int i = 0; i < totalTables; i++) {

				for (int j = 0; j < huffmanAlphabetSize; j++) {
					sortedFrequencyMap[j] = (tableFrequencies[i][j] << 9) | j;
				}
				Arrays.sort (sortedFrequencyMap);
				for (int j = 0; j < huffmanAlphabetSize; j++) {
					sortedFrequencies[j] = sortedFrequencyMap[j] >>> 9;
				}

				HuffmanAllocator.allocateHuffmanCodeLengths (sortedFrequencies, BZip2Constants.HUFFMAN_ENCODE_MAXIMUM_CODE_LENGTH);

				for (int j = 0; j < huffmanAlphabetSize; j++) {
					huffmanCodeLengths[i][sortedFrequencyMap[j] & 0x1ff] = sortedFrequencies[j];
				}

			}

		}

		return selectorIndex;

	}


	/**
	 * Assigns Canonical Huffman codes based on the calculated lengths
	 * @param totalTables The total number of Huffman tables
	 */
	private void assignHuffmanCodeSymbols (final int totalTables) {

		final int[][] huffmanMergedCodeSymbols = this.huffmanMergedCodeSymbols;
		final int[][] huffmanCodeLengths = this.huffmanCodeLengths;
		final int huffmanAlphabetSize = this.huffmanAlphabetSize;

		for (int i = 0; i < totalTables; i++) {

			final int[] tableLengths = huffmanCodeLengths[i];

			int minimumLength = 32;
			int maximumLength = 0;
			for (int j = 0; j < huffmanAlphabetSize; j++) {
				final int length = tableLengths[j];
				if (length > maximumLength) {
					maximumLength = length;
				}
				if (length < minimumLength) {
					minimumLength = length;
				}
			}

			int code = 0;
			for (int j = minimumLength; j <= maximumLength; j++) {
				for (int k = 0; k < huffmanAlphabetSize; k++) {
					if ((huffmanCodeLengths[i][k] & 0xff) == j) {
						huffmanMergedCodeSymbols[i][k] = (j << 24) | code;
						code++;
					}
				}
				code <<= 1;
			}

		}

	}


	/**
	 * Write the Huffman symbol to output byte map
	 * @throws IOException on any I/O error writing the data
	 */
	private void writeSymbolMap() throws IOException {

		BitOutputStream bitOutputStream = this.bitOutputStream;

		final boolean[] bwtValuesInUse = this.bwtValuesInUse;
		final boolean[] condensedInUse = new boolean[16];

		for (int i = 0; i < 16; i++) {
			for (int j = 0, k = i << 4; j < 16; j++, k++) {
				if (bwtValuesInUse[k]) {
					condensedInUse[i] = true;
				}
			}
		}

		for (int i = 0; i < 16; i++) {
			bitOutputStream.writeBoolean (condensedInUse[i]);
		}

		for (int i = 0; i < 16; i++) {
			if (condensedInUse[i]) {
				for (int j = 0, k = i * 16; j < 16; j++, k++) {
					bitOutputStream.writeBoolean (bwtValuesInUse[k]);
				}
			}
		}

	}


	/**
	 * Write total number of Huffman tables and selectors, and the MTFed Huffman selector list
	 * @param totaTables The total number of Huffman tables
	 * @param totalSelectors The total number of selectors
	 * @throws IOException on any I/O error writing the data
	 */
	private void writeSelectors (final int totaTables, final int totalSelectors) throws IOException {

		final BitOutputStream bitOutputStream = this.bitOutputStream;
		final byte[] selectors = this.selectors;

		bitOutputStream.writeBits (3, totaTables);
		bitOutputStream.writeBits (15, totalSelectors);

		MoveToFront selectorMTF = new MoveToFront();

		for (int i = 0; i < totalSelectors; i++) {
			bitOutputStream.writeUnary (selectorMTF.valueToFront (selectors[i]));
		}

	}


	/**
	 * Write the Canonical Huffman code lengths for each table
	 * @param totalTables The total number of tables
	 * @throws IOException on any I/O error writing the data
	 */
	private void writeHuffmanCodeLengths (final int totalTables) throws IOException {

		final BitOutputStream bitOutputStream = this.bitOutputStream;
		final int[][] huffmanCodeLengths = this.huffmanCodeLengths;
		final int huffmanAlphabetSize = this.huffmanAlphabetSize;

		for (int i = 0; i < totalTables; i++) {
			final int[] tableLengths = huffmanCodeLengths[i];
			int currentLength = tableLengths[0];

			bitOutputStream.writeBits (5, currentLength);

			for (int j = 0; j < huffmanAlphabetSize; j++) {
				final int codeLength = tableLengths[j];
				final int value = (currentLength < codeLength) ? 2 : 3;
				int delta = Math.abs (codeLength - currentLength);
				while (delta-- > 0) {
					bitOutputStream.writeBits (2, value);
				}
				bitOutputStream.writeBoolean (false);
				currentLength = codeLength;
			}
		}

	}


	/**
	 * Writes out the encoded block data
	 * @throws IOException on any I/O error writing the data
	 */
	private void writeBlockData() throws IOException {

		final BitOutputStream bitOutputStream = this.bitOutputStream;
		final int[][] huffmanMergedCodeSymbols = this.huffmanMergedCodeSymbols;
		final byte[] selectors = this.selectors;
		final char[] mtf = this.mtfBlock;
		final int mtfLength = this.mtfLength;

		int selectorIndex = 0;

		for (int mtfIndex = 0; mtfIndex < mtfLength;) {
			final int groupEnd = Math.min (mtfIndex + BZip2Constants.HUFFMAN_GROUP_RUN_LENGTH - 1, mtfLength - 1);
			final int[] tableMergedCodeSymbols = huffmanMergedCodeSymbols[selectors[selectorIndex++]];

			while (mtfIndex <= groupEnd) {
				final int mergedCodeSymbol = tableMergedCodeSymbols[mtf[mtfIndex++]];
				bitOutputStream.writeBits (mergedCodeSymbol >>> 24, mergedCodeSymbol);
			}
		}

	}


	/**
	 * Encodes and writes the block data
	 * @throws IOException on any I/O error writing the data
	 */
	public void encode() throws IOException {

		moveToFrontAndRunLengthEncode();

		final int totalTables = selectTableCount (this.mtfLength);
		generateInitialHuffmanCodeLengths (totalTables);
		final int totalSelectors = optimiseSelectorsAndHuffmanTables (totalTables);
		assignHuffmanCodeSymbols (totalTables);

		writeSymbolMap();
		writeSelectors (totalTables, totalSelectors);
		writeHuffmanCodeLengths (totalTables);
		writeBlockData();

	}


	/**
	 * @param bitOutputStream The BitOutputStream to write to
	 * @param bwtValuesInUse The byte values that are actually present within the block
	 * @param bwtBlock The Burrows Wheeler Transformed data
	 * @param bwtLength The actual length of the BWT data
	 */
	public BZip2HuffmanStageEncoder (final BitOutputStream bitOutputStream, final boolean[] bwtValuesInUse, final int[] bwtBlock, final int bwtLength) {

		this.bitOutputStream = bitOutputStream;
		this.mtfBlock = new char[2 * bwtBlock.length];
		this.bwtValuesInUse = bwtValuesInUse;
		this.bwtBlock = bwtBlock;
		this.bwtLength = bwtLength;

	}

}
