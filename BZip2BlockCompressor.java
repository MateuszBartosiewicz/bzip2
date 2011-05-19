package org.itadaki.bzip2;

import java.io.IOException;


/**
 * Compresses and writes a single BZip2 block
 */
public class BZip2BlockCompressor {

	/**
	 * A value outside the range of an unsigned byte that is placed in the {@link #rleCurrentValue}
	 * field when no repeated RLE character is currently being accumulated
	 */
	private static final int RLE_NOT_A_VALUE = 0xffff;

	/**
	 * The stream to which compressed BZip2 data is written
	 */
	private final BitOutputStream bitOutputStream;

	/**
	 * CRC builder for the block
	 */
	private final CRC32 crc = new CRC32();

	/**
	 * The RLE'd block data
	 */
	private final byte[] block;

	/**
	 * Current length of the RLE'd block data
	 */
	private int blockLength = 0;

	/**
	 * A limit beyond which new data will not be accepted into the block
	 */
	private final int blockLengthLimit;

	/**
	 * For each index, {@code true} if that value is present in the block data, otherwise
	 * {@code false}
	 */
	private final boolean[] blockValuesPresent = new boolean[256];

	/**
	 * The Burrows Wheeler Transformed block data
	 */
	private final int[] bwtBlock;

	/**
	 * The current RLE value being accumulated
	 */
	private int rleCurrentValue = RLE_NOT_A_VALUE;

	/**
	 * The repeat count of the current RLE value. Only valid when zero (initially) or when
	 * {@link #rleCurrentValue} is not {@link #RLE_NOT_A_VALUE}
	 */
	private int rleLength = 0;


	/**
	 * Writes an RLE run to the block array
	 * @param value The value to write
	 * @param runLength The run length of the value to write
	 */
	private void writeRun (int value, int runLength) {

		final int blockLength = this.blockLength;
		final byte[] block = this.block;

		this.blockValuesPresent[value] = true;
		this.crc.updateCRC (value, runLength);

		final byte byteValue = (byte) value;
		switch (runLength) {
			case 1:
				block[blockLength] = byteValue;
				this.blockLength = blockLength + 1;
				break;

			case 2:
				block[blockLength] = byteValue;
				block[blockLength + 1] = byteValue;
				this.blockLength = blockLength + 2;
				break;

			case 3:
				block[blockLength] = byteValue;
				block[blockLength + 1] = byteValue;
				block[blockLength + 2] = byteValue;
				this.blockLength = blockLength + 3;
				break;

			default:
				runLength -= 4;
				this.blockValuesPresent[runLength] = true;
				block[blockLength] = byteValue;
				block[blockLength + 1] = byteValue;
				block[blockLength + 2] = byteValue;
				block[blockLength + 3] = byteValue;
				block[blockLength + 4] = (byte)runLength;
				this.blockLength = blockLength + 5;
				break;
		}

	}


	/**
	 * Writes a byte to the block data, accumulating to RLE runs as necessary
	 * @param value The byte to write to the block data
	 * @return {@code true} if the byte was written, of {@code false} if the block is true
	 */
	public boolean write (final int value) {

		if (this.blockLength > this.blockLengthLimit) {
			return false;
		}

		final int rleCurrentValue = this.rleCurrentValue;
		final int rleLength = this.rleLength;

		if (rleCurrentValue == RLE_NOT_A_VALUE) {
			this.rleCurrentValue = value;
			this.rleLength = 1;
		} else if (rleCurrentValue == value) {
			if (rleLength == 254) {
				writeRun (rleCurrentValue & 0xff, 255);
				this.rleCurrentValue = RLE_NOT_A_VALUE;
			} else {
				this.rleLength = rleLength + 1;
			}
		} else {
			writeRun (rleCurrentValue & 0xff, rleLength);
			this.rleLength = 1;
			this.rleCurrentValue = value;
		}

		return true;

	}


	/**
	 * Writes an array of data to the data block
	 * @param data The data to write
	 * @param offset The offset within the input data to write from
	 * @param length The number of bytes of input data to write
	 * @return The actual number of input bytes written. May be less than the number requested, or
	 *         zero if the block is already full
	 */
	public int write (final byte[] data, int offset, int length) {

		int written = 0;

		while (length-- > 0) {
			if (!write (data[offset++])) {
				break;
			}
			written++;
		}

		return written;

	}


	/**
	 * Compresses and writes out the block
	 * @throws IOException on any I/O error writing the data
	 */
	public void close() throws IOException {

		if ((this.rleCurrentValue != RLE_NOT_A_VALUE) && (this.rleLength > 0)) {
			writeRun (this.rleCurrentValue & 0xff, this.rleLength);
		}

		this.block[this.blockLength] = this.block[0];

		BZip2DivSufSort divSufSort = new BZip2DivSufSort (this.block, this.bwtBlock, this.blockLength);
		int origPtr = divSufSort.bwt();

		this.bitOutputStream.writeBits (24, BZip2Constants.BLOCK_HEADER_MARKER_1);
		this.bitOutputStream.writeBits (24, BZip2Constants.BLOCK_HEADER_MARKER_2);
		this.bitOutputStream.writeInteger (this.crc.getCRC());
		this.bitOutputStream.writeBoolean (false); // We never create randomised blocks
		this.bitOutputStream.writeBits (24, origPtr);

		BZip2HuffmanStageEncoder huffmanEncoder = new BZip2HuffmanStageEncoder (this.bitOutputStream, this.blockValuesPresent, this.bwtBlock, this.blockLength);
		huffmanEncoder.encode();

	}


	/**
	 * Determines if any bytes have been written to the block
	 * @return {@code true} if one or more bytes has been written to the block, otherwise
	 *         {@code false}
	 */
	public boolean isEmpty() {

		return ((this.blockLength == 0) && (this.rleLength == 0));

	}


	/**
	 * Gets the CRC of the completed block. Only valid after calling {@link #close()}
	 * @return The block's CRC
	 */
	public int getCRC() {

		return this.crc.getCRC();

	}


	/**
	 * @param bitOutputStream The stream to which compressed BZip2 data is written
	 * @param blockSize The declared block size in bytes
	 */
	public BZip2BlockCompressor (BitOutputStream bitOutputStream, int blockSize) {

		this.bitOutputStream = bitOutputStream;

		this.block = new byte[blockSize];
		this.bwtBlock = new int[blockSize];
		this.blockLengthLimit = blockSize - 5; // 4 bytes for one RLE run plus a one byte block wrap


	}

}
