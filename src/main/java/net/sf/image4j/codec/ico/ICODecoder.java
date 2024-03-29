/*
 * ICODecoder.java
 *
 * Created on May 9, 2006, 9:31 PM
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

package net.sf.image4j.codec.ico;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import net.sf.image4j.codec.bmp.BMPDecoder;
import net.sf.image4j.codec.bmp.ColorEntry;
import net.sf.image4j.codec.bmp.InfoHeader;
import net.sf.image4j.io.CountingInputStream;
import net.sf.image4j.io.EndianUtils;
import net.sf.image4j.io.LittleEndianInputStream;

/**
 * Decodes images in ICO format.
 * 
 * @author Ian McDonagh
 */
public class ICODecoder
{

	private static Logger log = Logger.getLogger(ICODecoder.class.getName());

	private static final int PNG_MAGIC = 0x89504E47;
	private static final int PNG_MAGIC_LE = 0x474E5089;
	private static final int PNG_MAGIC2 = 0x0D0A1A0A;
	private static final int PNG_MAGIC2_LE = 0x0A1A0A0D;

	// private List<BufferedImage> img;

	private ICODecoder()
	{
	}

	/**
	 * Reads and decodes the given ICO file. Convenience method equivalent to
	 * {@link #read(InputStream) read(new FileInputStream(file))}.
	 * 
	 * @param file
	 *            the source file to read
	 * @return the list of images decoded from the ICO data
	 * @throws IOException
	 *             if an error occurs
	 */
	public static List<BufferedImage> read(File file) throws IOException
	{
		FileInputStream fin = new FileInputStream(file);
		try {
			return read(new BufferedInputStream(fin));
		} finally {
			try {
				fin.close();
			} catch (IOException ex) {
				log.log(Level.FINE,
						"Failed to close file input for file " + file);
			}
		}
	}

	/**
	 * Reads and decodes the given ICO file, together with all metadata.
	 * Convenience method equivalent to {@link #readExt(InputStream) readExt(new
	 * FileInputStream(file))}.
	 * 
	 * @param file
	 *            the source file to read
	 * @return the list of images decoded from the ICO data
	 * @throws IOException
	 *             if an error occurs
	 * @since 0.7
	 */
	public static List<ICOImage> readExt(File file) throws IOException
	{
		FileInputStream fin = new FileInputStream(file);
		try {
			return readExt(new BufferedInputStream(fin));
		} finally {
			try {
				fin.close();
			} catch (IOException ex) {
				log.log(Level.WARNING,
						"Failed to close file input for file " + file, ex);
			}
		}
	}

	/**
	 * Reads and decodes ICO data from the given source. The returned list of
	 * images is in the order in which they appear in the source ICO data.
	 * 
	 * @param is
	 *            the source <tt>InputStream</tt> to read
	 * @return the list of images decoded from the ICO data
	 * @throws IOException
	 *             if an error occurs
	 */
	public static List<BufferedImage> read(InputStream is) throws IOException
	{
		List<ICOImage> list = readExt(is);
		List<BufferedImage> ret = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			ICOImage icoImage = list.get(i);
			BufferedImage image = icoImage.getImage();
			ret.add(image);
		}
		return ret;
	}

	private static IconEntry[] sortByFileOffset(IconEntry[] entries)
	{
		List<IconEntry> list = Arrays.asList(entries);
		Collections.sort(list, new Comparator<IconEntry>() {

			@Override
			public int compare(IconEntry o1, IconEntry o2)
			{
				return o1.iFileOffset - o2.iFileOffset;
			}
		});
		return list.toArray(new IconEntry[list.size()]);
	}

	/**
	 * Reads and decodes ICO data from the given source, together with all
	 * metadata. The returned list of images is in the order in which they
	 * appear in the source ICO data.
	 * 
	 * @param is
	 *            the source <tt>InputStream</tt> to read
	 * @return the list of images decoded from the ICO data
	 * @throws IOException
	 *             if an error occurs
	 * @since 0.7
	 */
	public static List<ICOImage> readExt(InputStream is) throws IOException
	{
		// long t = System.currentTimeMillis();

		LittleEndianInputStream in = new LittleEndianInputStream(
				new CountingInputStream(is));

		// Reserved 2 byte =0
		short sReserved = in.readShortLE();
		// Type 2 byte =1
		short sType = in.readShortLE();
		// Count 2 byte Number of Icons in this file
		short sCount = in.readShortLE();

		// Entries Count * 16 list of icons
		IconEntry[] entries = new IconEntry[sCount];
		for (short s = 0; s < sCount; s++) {
			entries[s] = new IconEntry(in);
		}
		// Seems like we don't need this, but you never know!
		// entries = sortByFileOffset(entries);

		int i = 0;
		// images list of bitmap structures in BMP/PNG format
		List<ICOImage> ret = new ArrayList<>(sCount);

		try {
			for (i = 0; i < sCount; i++) {
				// Make sure we're at the right file offset!
				int fileOffset = in.getCount();
				if (fileOffset != entries[i].iFileOffset) {
					throw new IOException("Cannot read image #" + i
							+ " starting at unexpected file offset.");
				}
				int info = in.readIntLE();
				log.log(Level.FINE, "Image #" + i + " @ " + in.getCount()
						+ " info = " + EndianUtils.toInfoString(info));
				if (info == 40) {

					// read XOR bitmap
					// BMPDecoder bmp = new BMPDecoder(is);
					InfoHeader infoHeader = BMPDecoder.readInfoHeader(in, info);
					InfoHeader andHeader = new InfoHeader(infoHeader);
					andHeader.iHeight = infoHeader.iHeight / 2;
					InfoHeader xorHeader = new InfoHeader(infoHeader);
					xorHeader.iHeight = andHeader.iHeight;

					andHeader.sBitCount = 1;
					andHeader.iNumColors = 2;

					// for now, just read all the raster data (xor + and)
					// and store as separate images

					BufferedImage xor = BMPDecoder.read(xorHeader, in);
					// If we want to be sure we've decoded the XOR mask
					// correctly,
					// we can write it out as a PNG to a temp file here.
					// try {
					// File temp = File.createTempFile("image4j", ".png");
					// ImageIO.write(xor, "png", temp);
					// log.info("Wrote xor mask for image #" + i + " to "
					// + temp.getAbsolutePath());
					// } catch (Throwable ex) {
					// }
					// Or just add it to the output list:
					// img.add(xor);

					BufferedImage img = new BufferedImage(xorHeader.iWidth,
							xorHeader.iHeight, BufferedImage.TYPE_INT_ARGB);

					ColorEntry[] andColorTable = new ColorEntry[] {
							new ColorEntry(255, 255, 255, 255),
							new ColorEntry(0, 0, 0, 0) };

					if (infoHeader.sBitCount == 32) {
						// transparency from alpha
						// ignore bytes after XOR bitmap
						int size = entries[i].iSizeInBytes;
						int infoHeaderSize = infoHeader.iSize;
						// data size = w * h * 4
						int dataSize = xorHeader.iWidth * xorHeader.iHeight * 4;
						int skip = size - infoHeaderSize - dataSize;
						int skip2 = entries[i].iFileOffset + size
								- in.getCount();

						// ignore AND bitmap since alpha channel stores
						// transparency

						if (in.skip(skip, false) < skip && i < sCount - 1) {
							throw new EOFException("Unexpected end of input");
						}
						// If we skipped less bytes than expected, the AND mask
						// is probably badly formatted.
						// If we're at the last/only entry in the file, silently
						// ignore and continue processing...

						// //read AND bitmap
						// BufferedImage and = BMPDecoder.read(andHeader, in,
						// andColorTable);
						// this.img.add(and);

						WritableRaster srgb = xor.getRaster();
						WritableRaster salpha = xor.getAlphaRaster();
						WritableRaster rgb = img.getRaster();
						WritableRaster alpha = img.getAlphaRaster();

						for (int y = xorHeader.iHeight - 1; y >= 0; y--) {
							for (int x = 0; x < xorHeader.iWidth; x++) {
								int r = srgb.getSample(x, y, 0);
								int g = srgb.getSample(x, y, 1);
								int b = srgb.getSample(x, y, 2);
								int a = salpha.getSample(x, y, 0);
								rgb.setSample(x, y, 0, r);
								rgb.setSample(x, y, 1, g);
								rgb.setSample(x, y, 2, b);
								alpha.setSample(x, y, 0, a);
							}
						}

					} else {
						BufferedImage and = BMPDecoder.read(andHeader, in,
								andColorTable);
						// img.add(and);

						// copy rgb
						WritableRaster srgb = xor.getRaster();
						WritableRaster rgb = img.getRaster();
						// copy alpha
						WritableRaster alpha = img.getAlphaRaster();
						WritableRaster salpha = and.getRaster();

						for (int y = 0; y < xorHeader.iHeight; y++) {
							for (int x = 0; x < xorHeader.iWidth; x++) {
								int r, g, b;
								int c = xor.getRGB(x, y);
								r = (c >> 16) & 0xFF;
								g = (c >> 8) & 0xFF;
								b = (c) & 0xFF;
								// red
								rgb.setSample(x, y, 0, r);
								// green
								rgb.setSample(x, y, 1, g);
								// blue
								rgb.setSample(x, y, 2, b);
								// System.out.println(x+","+y+"="+Integer.toHexString(c));
								// img.setRGB(x, y, c);

								// alpha
								int a = and.getRGB(x, y);
								alpha.setSample(x, y, 0, a);
							}
						}
					}
					// create ICOImage
					IconEntry iconEntry = entries[i];
					ICOImage icoImage = new ICOImage(img, infoHeader,
							iconEntry);
					icoImage.setPngCompressed(false);
					icoImage.setIconIndex(i);
					ret.add(icoImage);
				}
				// check for PNG magic header and that image height and width =
				// 0 = 256 -> Vista format
				else if (info == PNG_MAGIC_LE) {

					int info2 = in.readIntLE();

					if (info2 != PNG_MAGIC2_LE) {
						throw new IOException(
								"Unrecognized icon format for image #" + i);
					}

					IconEntry e = entries[i];
					int size = e.iSizeInBytes - 8;
					byte[] pngData = new byte[size];
					/* int count = */in.readFully(pngData);
					// if (count != pngData.length) {
					// throw new
					// IOException("Unable to read image #"+i+" - incomplete PNG
					// compressed data");
					// }
					ByteArrayOutputStream bout = new ByteArrayOutputStream();
					DataOutputStream dout = new DataOutputStream(bout);
					dout.writeInt(PNG_MAGIC);
					dout.writeInt(PNG_MAGIC2);
					dout.write(pngData);
					byte[] pngData2 = bout.toByteArray();
					ByteArrayInputStream bin = new ByteArrayInputStream(
							pngData2);
					ImageInputStream input = ImageIO
							.createImageInputStream(bin);
					ImageReader reader = getPNGImageReader();
					reader.setInput(input);
					BufferedImage img = reader.read(0);

					// create ICOImage
					IconEntry iconEntry = entries[i];
					ICOImage icoImage = new ICOImage(img, null, iconEntry);
					icoImage.setPngCompressed(true);
					icoImage.setIconIndex(i);
					ret.add(icoImage);
				} else {
					throw new IOException(
							"Unrecognized icon format for image #" + i);
				}

				/*
				 * InfoHeader andInfoHeader = new InfoHeader();
				 * andInfoHeader.iColorsImportant = 0; andInfoHeader.iColorsUsed
				 * = 0; andInfoHeader.iCompression = BMPConstants.BI_RGB;
				 * andInfoHeader.iHeight = xorInfoHeader.iHeight / 2;
				 * andInfoHeader.iWidth = xorInfoHeader.
				 */
			}
		} catch (IOException ex) {
			throw new IOException("Failed to read image # " + i, ex);
		}

		// long t2 = System.currentTimeMillis();
		// System.out.println("Loaded ICO file in "+(t2 - t)+"ms");

		return ret;
	}

	private static ImageReader getPNGImageReader()
	{
		ImageReader ret = null;
		Iterator<ImageReader> itr = ImageIO.getImageReadersByFormatName("png");
		if (itr.hasNext()) {
			ret = itr.next();
		}
		return ret;
	}
}
