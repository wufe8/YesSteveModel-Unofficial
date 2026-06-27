package rip.ysm.imagestream.webp.data;

import rip.ysm.imagestream.utility.DataByteLittle;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.List;

class WDecoder {
   private static final int PREDICTOR_TRANSFORM = 0;
   private static final int COLOR_TRANSFORM = 1;
   private static final int SUBTRACT_GREEN = 2;
   private static final int COLOR_INDEXING_TRANSFORM = 3;
   private static final byte[] DISTANCES = new byte[]{
      24,
      7,
      23,
      25,
      40,
      6,
      39,
      41,
      22,
      26,
      38,
      42,
      56,
      5,
      55,
      57,
      21,
      27,
      54,
      58,
      37,
      43,
      72,
      4,
      71,
      73,
      20,
      28,
      53,
      59,
      70,
      74,
      36,
      44,
      88,
      69,
      75,
      52,
      60,
      3,
      87,
      89,
      19,
      29,
      86,
      90,
      35,
      45,
      68,
      76,
      85,
      91,
      51,
      61,
      104,
      2,
      103,
      105,
      18,
      30,
      102,
      106,
      34,
      46,
      84,
      92,
      67,
      77,
      101,
      107,
      50,
      62,
      120,
      1,
      119,
      121,
      83,
      93,
      17,
      31,
      100,
      108,
      66,
      78,
      118,
      122,
      33,
      47,
      117,
      123,
      49,
      63,
      99,
      109,
      82,
      94,
      0,
      116,
      124,
      65,
      79,
      16,
      32,
      98,
      110,
      48,
      115,
      125,
      81,
      95,
      64,
      114,
      126,
      97,
      111,
      80,
      113,
      127,
      96,
      112
   };
   private final DataByteLittle reader;
   private final WBit wbit;

   WDecoder(DataByteLittle reader) {
      this.reader = reader;
      this.wbit = new WBit(reader);
   }

   void decodeImageStream(WritableRaster raster, boolean topLevel, int width, int height) {
      if (topLevel) {
         this.reader.moveTo(this.reader.getPosition() + 5);
      }

      int xSize = width;
      ArrayList<WTransform> transforms = new ArrayList<>();

      while (topLevel && this.wbit.readBit() == 1) {
         xSize = this.readTransform(xSize, height, transforms);
      }

      int colorMapBits = 0;
      if (this.wbit.readBit() == 1) {
         colorMapBits = this.wbit.readBits(4);
      }

      HuffmanInfo huffmanInfo = this.readHuffmanCodes(xSize, height, colorMapBits, topLevel);
      ColorMap colorMap = null;
      if (colorMapBits > 0) {
         colorMap = new ColorMap(colorMapBits);
      }

      WritableRaster fullSizeRaster;
      WritableRaster decodeRaster;
      if (topLevel) {
         fullSizeRaster = raster;
         decodeRaster = raster.createWritableChild(0, 0, xSize, height, 0, 0, null);
      } else {
         fullSizeRaster = raster;
         decodeRaster = raster;
      }

      this.decodeImage(decodeRaster, huffmanInfo, colorMap);

      for (WTransform transform : transforms) {
         transform.perform(fullSizeRaster);
      }
   }

   private void decodeImage(WritableRaster raster, HuffmanInfo huffmanInfo, ColorMap colorMap) {
      int width = raster.getWidth();
      int height = raster.getHeight();
      int huffmanMask = huffmanInfo.metaCodeBits == 0 ? -1 : (1 << huffmanInfo.metaCodeBits) - 1;
      HuffmanGroup curCodeGroup = huffmanInfo.huffmanGroups[0];
      byte[] rgba = new byte[4];

      for (int y = 0; y < height; y++) {
         for (int x = 0; x < width; x++) {
            if ((x & huffmanMask) == 0 && huffmanInfo.huffmanMetaCodes != null) {
               int index = huffmanInfo.huffmanMetaCodes.getSample(x >> huffmanInfo.metaCodeBits, y >> huffmanInfo.metaCodeBits, 0);
               curCodeGroup = huffmanInfo.huffmanGroups[index];
            }

            short code = curCodeGroup.mainCode.readSymbol(this.wbit);
            if (code < 256) {
               this.decodeLiteral(raster, colorMap, curCodeGroup, rgba, y, x, code);
            } else if (code < 280) {
               int length = this.decodeBackwardReference(raster, colorMap, width, curCodeGroup, rgba, code, x, y);
               x--;
               y += (x + length) / width;
               x = (x + length) % width;
               if (y < height && x < width && huffmanInfo.huffmanMetaCodes != null) {
                  int index = huffmanInfo.huffmanMetaCodes.getSample(x >> huffmanInfo.metaCodeBits, y >> huffmanInfo.metaCodeBits, 0);
                  curCodeGroup = huffmanInfo.huffmanGroups[index];
               }
            } else {
               decodeCached(raster, colorMap, rgba, y, x, code);
            }
         }
      }
   }

   private static void decodeCached(WritableRaster raster, ColorMap colorCache, byte[] rgba, int y, int x, short code) {
      int argb = colorCache.lookup(code - 256 - 24);
      rgba[0] = (byte)(argb >> 16 & 0xFF);
      rgba[1] = (byte)(argb >> 8 & 0xFF);
      rgba[2] = (byte)(argb & 0xFF);
      rgba[3] = (byte)(argb >>> 24);
      raster.setDataElements(x, y, rgba);
   }

   private void decodeLiteral(WritableRaster raster, ColorMap colorCache, HuffmanGroup curCodeGroup, byte[] rgba, int y, int x, short code) {
      byte red = (byte)curCodeGroup.redCode.readSymbol(this.wbit);
      byte blue = (byte)curCodeGroup.blueCode.readSymbol(this.wbit);
      byte alpha = (byte)curCodeGroup.alphaCode.readSymbol(this.wbit);
      rgba[0] = red;
      rgba[1] = (byte)code;
      rgba[2] = blue;
      rgba[3] = alpha;
      raster.setDataElements(x, y, rgba);
      if (colorCache != null) {
         colorCache.insert((alpha & 255) << 24 | (red & 255) << 16 | (code & 255) << 8 | blue & 255);
      }
   }

   private int decodeBackwardReference(WritableRaster raster, ColorMap colorMap, int width, HuffmanGroup curCodeGroup, byte[] rgba, short code, int x, int y) {
      int length = this.getCopyDistance(code - 256);
      short distancePrefix = curCodeGroup.distanceCode.readSymbol(this.wbit);
      int distanceCode = this.getCopyDistance(distancePrefix);
      int xSrc;
      int ySrc;
      if (distanceCode > 120) {
         int distance = distanceCode - 120;
         ySrc = y - distance / width;
         xSrc = x - distance % width;
      } else {
         xSrc = x - (8 - (DISTANCES[distanceCode - 1] & 15));
         ySrc = y - (DISTANCES[distanceCode - 1] >> 4);
      }

      if (xSrc < 0) {
         ySrc--;
         xSrc += width;
      } else if (xSrc >= width) {
         xSrc -= width;
         ySrc++;
      }

      for (int l = length; l > 0; l--) {
         if (x == width) {
            x = 0;
            y++;
         }

         raster.getDataElements(xSrc++, ySrc, rgba);
         raster.setDataElements(x, y, rgba);
         if (xSrc == width) {
            xSrc = 0;
            ySrc++;
         }

         if (colorMap != null) {
            colorMap.insert((rgba[3] & 255) << 24 | (rgba[0] & 255) << 16 | (rgba[1] & 255) << 8 | rgba[2] & 255);
         }

         x++;
      }

      return length;
   }

   private int getCopyDistance(int prefixCode) {
      if (prefixCode < 4) {
         return prefixCode + 1;
      } else {
         int extraBits = prefixCode - 2 >> 1;
         int offset = 2 + (prefixCode & 1) << extraBits;
         return offset + this.wbit.readBits(extraBits) + 1;
      }
   }

   private int readTransform(int xSize, int ySize, List<WTransform> transforms) {
      int transformType = this.wbit.readBits(2);
      switch (transformType) {
         case 0:
         case 1:
            this.readPredictorAndColorTransformInfo(xSize, ySize, transforms, transformType);
            break;
         case 2:
            transforms.add(0, new SubtractGreen());
            break;
         case 3:
            int colorTableSize = this.wbit.readBits(8) + 1;
            int safeColorTableSize = colorTableSize > 16 ? 256 : (colorTableSize > 4 ? 16 : (colorTableSize > 2 ? 4 : 2));
            byte[] colorTable = new byte[safeColorTableSize * 4];
            byte widthBits = (byte)(colorTableSize > 16 ? 0 : (colorTableSize > 4 ? 1 : (colorTableSize > 2 ? 2 : 3)));
            xSize = subSampleSize(xSize, widthBits);
            this.decodeImageStream(
               Raster.createInterleavedRaster(
                  new DataBufferByte(colorTable, colorTableSize * 4), colorTableSize, 1, colorTableSize * 4, 4, new int[]{0, 1, 2, 3}, null
               ),
               false,
               colorTableSize,
               1
            );

            for (int i = 4; i < colorTable.length; i++) {
               colorTable[i] += colorTable[i - 4];
            }

            transforms.add(0, new ColorIndexing(colorTable, widthBits));
      }

      return xSize;
   }

   private void readPredictorAndColorTransformInfo(int xSize, int ySize, List<WTransform> transforms, int transformType) {
      byte sizeBits = (byte)(this.wbit.readBits(3) + 2);
      int blockWidth = subSampleSize(xSize, sizeBits);
      int blockHeight = subSampleSize(ySize, sizeBits);
      BufferedImage img = new BufferedImage(blockWidth, blockHeight, 6);
      WritableRaster raster = img.getRaster();
      this.decodeImageStream(raster, false, blockWidth, blockHeight);
      if (transformType == 0) {
         transforms.add(0, new Predictor(raster, sizeBits));
      } else {
         transforms.add(0, new ColorTransform(raster, sizeBits));
      }
   }

   private HuffmanInfo readHuffmanCodes(int xSize, int ySize, int colorCacheBits, boolean readMetaCodes) {
      int huffmanGroupNum = 1;
      int metaCodeBits = 0;
      WritableRaster huffmanMetaCodes = null;
      if (readMetaCodes && this.wbit.readBit() == 1) {
         metaCodeBits = this.wbit.readBits(3) + 2;
         int huffmanXSize = subSampleSize(xSize, metaCodeBits);
         int huffmanYSize = subSampleSize(ySize, metaCodeBits);
         WritableRaster packedRaster = Raster.createPackedRaster(3, huffmanXSize, huffmanYSize, new int[]{65280, 255, -16777216, 16711680}, null);
         this.decodeImageStream(getOptimizedRaster(packedRaster), false, huffmanXSize, huffmanYSize);
         int[] data = ((DataBufferInt)packedRaster.getDataBuffer()).getData();
         int maxCode = Integer.MIN_VALUE;

         for (int code : data) {
            maxCode = Math.max(maxCode, code & 65535);
         }

         huffmanGroupNum = maxCode + 1;
         huffmanMetaCodes = Raster.createPackedRaster(packedRaster.getDataBuffer(), huffmanXSize, huffmanYSize, huffmanXSize, new int[]{65535}, null);
      }

      HuffmanGroup[] huffmanGroups = new HuffmanGroup[huffmanGroupNum];

      for (int i = 0; i < huffmanGroups.length; i++) {
         huffmanGroups[i] = new HuffmanGroup(this.wbit, colorCacheBits);
      }

      return new HuffmanInfo(huffmanMetaCodes, metaCodeBits, huffmanGroups);
   }

   private static int subSampleSize(int size, int samplingBits) {
      return size + (1 << samplingBits) - 1 >> samplingBits;
   }

   private static WritableRaster getOptimizedRaster(WritableRaster raster) {
      switch (raster.getTransferType()) {
         case 0:
            return raster;
         default:
            SampleModel sampleModel = raster.getSampleModel();
            int bands = 4;
            final DataBufferInt buffer = (DataBufferInt)raster.getDataBuffer();
            int w = raster.getWidth();
            int h = raster.getHeight();
            int size = buffer.getSize();
            return new WritableRaster(
               new PixelInterleavedSampleModel(0, w, h, 4, w * 4, createBandOffsets((SinglePixelPackedSampleModel)sampleModel)), new DataBuffer(0, size * 4) {
                  final int[] MASKS = new int[]{-256, -65281, -16711681, 16777215};

                  @Override
                  public int getElem(int bank, int i) {
                     int index = i / 4;
                     int shift = i % 4 * 8;
                     return buffer.getElem(index) >>> shift & 0xFF;
                  }

                  @Override
                  public void setElem(int bank, int i, int val) {
                     int index = i / 4;
                     int element = i % 4;
                     int shift = element * 8;
                     int value = buffer.getElem(index) & this.MASKS[element] | (val & 0xFF) << shift;
                     buffer.setElem(index, value);
                  }
               }, new Point()
            ) {};
      }
   }

   private static int[] createBandOffsets(SinglePixelPackedSampleModel sampleModel) {
      int[] masks = sampleModel.getBitMasks();
      int[] offs = new int[masks.length];

      for (int i = 0; i < masks.length; i++) {
         int mask = masks[i];
         int off = 0;
         if (mask != 0) {
            while ((mask & 0xFF) == 0) {
               mask >>>= 8;
               off++;
            }
         }

         offs[i] = off;
      }

      return offs;
   }
}
