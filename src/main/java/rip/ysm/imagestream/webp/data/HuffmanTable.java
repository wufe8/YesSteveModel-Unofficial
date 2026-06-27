package rip.ysm.imagestream.webp.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class HuffmanTable {
   private static final int LEVEL1_BITS = 8;
   private static final int[] KCODELENGTHCODEORDER = new int[]{17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
   private final int[] level1 = new int[256];
   private final List<int[]> level2 = new ArrayList<>();

   HuffmanTable(WBit wBit, int alphabetSize) {
      boolean simpleLengthCode = wBit.readBit() == 1;
      if (simpleLengthCode) {
         int symbolNum = wBit.readBit() + 1;
         boolean first8Bits = wBit.readBit() == 1;
         short symbol1 = (short)wBit.readBits(first8Bits ? 8 : 1);
         if (symbolNum == 2) {
            short symbol2 = (short)wBit.readBits(8);

            for (int i = 0; i < 256; i += 2) {
               this.level1[i] = 65536 | symbol1;
               this.level1[i + 1] = 65536 | symbol2;
            }
         } else {
            Arrays.fill(this.level1, symbol1);
         }
      } else {
         int numLCodeLengths = wBit.readBits(4) + 4;
         short[] lCodeLengths = new short[KCODELENGTHCODEORDER.length];
         int numPosCodeLens = 0;

         for (int i = 0; i < numLCodeLengths; i++) {
            short len = (short)wBit.readBits(3);
            lCodeLengths[KCODELENGTHCODEORDER[i]] = len;
            if (len > 0) {
               numPosCodeLens++;
            }
         }

         short[] codeLengths = readCodeLengths(wBit, lCodeLengths, alphabetSize, numPosCodeLens);
         this.buildFromLengths(codeLengths);
      }
   }

   private HuffmanTable(short[] codeLengths, int numPosCodeLens) {
      this.buildFromLengths(codeLengths, numPosCodeLens);
   }

   private void buildFromLengths(short[] codeLengths) {
      int numPosCodeLens = 0;

      for (short codeLength : codeLengths) {
         if (codeLength != 0) {
            numPosCodeLens++;
         }
      }

      this.buildFromLengths(codeLengths, numPosCodeLens);
   }

   private void buildFromLengths(short[] codeLengths, int numPosCodeLens) {
      int[] lengthsAndSymbols = new int[numPosCodeLens];
      int index = 0;

      for (int i = 0; i < codeLengths.length; i++) {
         if (codeLengths[i] != 0) {
            lengthsAndSymbols[index++] = codeLengths[i] << 16 | i;
         }
      }

      if (numPosCodeLens == 1) {
         Arrays.fill(this.level1, lengthsAndSymbols[0] & 65535);
      }

      Arrays.sort(lengthsAndSymbols);
      int code = 0;
      int rootEntry = -1;
      int[] currentTable = null;

      for (int ix = 0; ix < lengthsAndSymbols.length; ix++) {
         int lengthAndSymbol = lengthsAndSymbols[ix];
         int length = lengthAndSymbol >>> 16;
         if (length <= 8) {
            for (int j = code; j < this.level1.length; j += 1 << length) {
               this.level1[j] = lengthAndSymbol;
            }
         } else {
            if ((code & 0xFF) != rootEntry) {
               int maxLength = length;
               int j = ix;

               for (int openSlots = 1 << length - 8; j < lengthsAndSymbols.length && openSlots > 0; openSlots--) {
                  for (int innerLength = lengthsAndSymbols[j] >>> 16; innerLength != maxLength; openSlots <<= 1) {
                     maxLength++;
                  }

                  j++;
               }

               j = maxLength - 8;
               currentTable = new int[1 << j];
               rootEntry = code & 0xFF;
               this.level2.add(currentTable);
               this.level1[rootEntry] = 8 + j << 16 | this.level2.size() - 1;
            }

            if (currentTable != null) {
               for (int j = code >>> 8; j < currentTable.length; j += 1 << length - 8) {
                  currentTable[j] = length - 8 << 16 | lengthAndSymbol & 65535;
               }
            }
         }

         code = nextCode(code, length);
      }
   }

   private static int nextCode(int code, int length) {
      int a = ~code & (1 << length) - 1;
      int step = Integer.highestOneBit(a);
      return code & step - 1 | step;
   }

   private static short[] readCodeLengths(WBit wBit, short[] aCodeLengths, int alphabetSize, int numPosCodeLens) {
      HuffmanTable huffmanTable = new HuffmanTable(aCodeLengths, numPosCodeLens);
      int codedSymbols;
      if (wBit.readBit() == 1) {
         int maxSymbolBitLength = 2 + 2 * wBit.readBits(3);
         codedSymbols = 2 + wBit.readBits(maxSymbolBitLength);
      } else {
         codedSymbols = alphabetSize;
      }

      short[] codeLengths = new short[alphabetSize];
      short prevLength = 8;

      for (int i = 0; i < alphabetSize && codedSymbols > 0; codedSymbols--) {
         short len = huffmanTable.readSymbol(wBit);
         if (len < 16) {
            codeLengths[i] = len;
            if (len != 0) {
               prevLength = len;
            }
         } else {
            short repeatSymbol = 0;
            int extraBits = 0;
            int repeatOffset = 0;
            switch (len) {
               case 16:
                  repeatSymbol = prevLength;
                  extraBits = 2;
                  repeatOffset = 3;
                  break;
               case 17:
                  extraBits = 3;
                  repeatOffset = 3;
                  break;
               case 18:
                  extraBits = 7;
                  repeatOffset = 11;
            }

            int repeatCount = wBit.readBits(extraBits) + repeatOffset;
            Arrays.fill(codeLengths, i, i + repeatCount, repeatSymbol);
            i += repeatCount - 1;
         }

         i++;
      }

      return codeLengths;
   }

   short readSymbol(WBit wBit) {
      int index = wBit.readForward(8);
      int lengthAndSymbol = this.level1[index];
      int length = lengthAndSymbol >>> 16;
      if (length > 8) {
         wBit.readBits(8);
         int level2Index = wBit.readForward(length - 8);
         lengthAndSymbol = this.level2.get(lengthAndSymbol & 65535)[level2Index];
         length = lengthAndSymbol >>> 16;
      }

      wBit.readBits(length);
      return (short)(lengthAndSymbol & 65535);
   }
}
