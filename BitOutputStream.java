package org.itadaki.bzip2;

import java.io.IOException;
import java.io.OutputStream;

/**
 * <p>An OutputStream wrapper that writes (multiples of) single bits, flushing a byte at a time to
 * the wrapped stream when sufficient bits have been written</p>
 */
public class BitOutputStream {

	/**
	 * The stream to which bits are written
	 */
	private final OutputStream outputStream;

	/**
	 * A buffer of bits to write to the output stream
	 */
	private int bitBuffer;

	/**
	 * The number of bits currently buffered in {@link #bitBuffer}
	 */
	private int bitCount;


	/**
	 * Writes a single bit to the wrapped output stream
	 * @param value The bit to write
	 * @throws IOException if an error occurs writing to the stream
	 */
	public void writeBoolean (final boolean value) throws IOException {

		int bitCount = this.bitCount + 1;
		int bitBuffer = this.bitBuffer | ((value ? 1 : 0) << (32 - bitCount));

		if (bitCount == 8) {
			this.outputStream.write (bitBuffer >> 24);
			bitBuffer = 0;
			bitCount = 0;
		}

		this.bitBuffer = bitBuffer;
		this.bitCount = bitCount;

	}


	/**
	 * Writes a zero-terminated unary number to the wrapped output stream
	 * @param value The number to write (must be non-negative)
	 * @throws IOException if an error occurs writing to the stream
	 */
	public void writeUnary (int value) throws IOException {

		while (value-- > 0) {
			writeBoolean (true); 
		}
		writeBoolean (false);

	}


	/**
	 * Writes up to 24 bits to the wrapped output stream
	 * @param count The number of bits to write (maximum 24)
	 * @param value The bits to write
	 * @throws IOException if an error occurs writing to the stream
	 */
	public void writeBits (final int count, final int value) throws IOException {

		int bitCount = this.bitCount + count;
		int bitBuffer = this.bitBuffer | (value << (32 - bitCount));

		while (bitCount >= 8) {
			this.outputStream.write (bitBuffer >> 24);
			bitBuffer <<= 8;
			bitCount -= 8;
		}

		this.bitBuffer = bitBuffer;
		this.bitCount = bitCount;

	}


	/**
	 * Writes an integer as 32 bits of output
	 * @param value The integer to write
	 * @throws IOException if an error occurs writing to the stream
	 */
	public void writeInteger (final int value) throws IOException {

		writeBits (16, (value >> 16) & 0xffff);
		writeBits (16, value & 0xffff);

	}


	/**
	 * Writes any remaining bits to the output stream, padding to a whole byte as required
	 * @throws IOException if an error occurs writing to the stream
	 */
	public void flush() throws IOException {

		if (this.bitCount > 0) {
			writeBits (8 - this.bitCount, 0);
		}

	}


	/**
	 * @param outputStream The OutputStream to wrap
	 */
	public BitOutputStream (final OutputStream outputStream) {

		this.outputStream = outputStream;

	}

}
