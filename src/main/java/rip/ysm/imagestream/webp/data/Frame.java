package rip.ysm.imagestream.webp.data;

import rip.ysm.imagestream.utility.DataByteLittle;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.List;

public class Frame {
   private static final int BLOCK_TYPES = 4;
   private static final int COEF_BANDS = 8;
   private static final int MAX_ENTROPY_TOKENS = 12;
   private static final int MAX_MODE_LF_DELTAS = 4;
   private static final int MAX_REF_LF_DELTAS = 4;
   private static final int PREV_COEF_CONTEXTS = 3;
   private final int[][][][] coefProbs;
   private int filterLevel;
   private int filterType;
   private int frameType;
   private final DataByteLittle reader;
   private int height;
   private int mbCols;
   private int macroBlockNoCoeffSkip;
   private int mbRows;
   private MacroBlock[][] macroBlocks;
   private int macroBlockSegementAbsoluteDelta;
   private int[] macroBlockSegmentTreeProbs;
   private final int[] modeLoopFilterDeltas = new int[4];
   private int modeRefLoopFilterDeltaEnabled;
   private int multiTokenPartition;
   private int offset;
   private final int[] refLoopFilterDeltas = new int[4];
   private int segmentationIsEnabled;
   private SegmentQuants segmentQuants;
   private int sharpnessLevel;
   private BitDecoder tokenBoolDecoder;
   private final List<BitDecoder> tokenBitDecoders;
   private int mBlockMap;
   private int width;

   public Frame(DataByteLittle stream) {
      this.reader = stream;
      this.offset = this.reader.getPosition();
      this.coefProbs = LookUp.getClonedDCP();
      this.tokenBitDecoders = new ArrayList<>();
   }

   public static BufferedImage decodeLossless(byte[] raw) {
      DataByteLittle db = new DataByteLittle(raw);
      WBit br = new WBit(db);
      br.readBits(8);
      int iw = br.readBits(14) + 1;
      int ih = br.readBits(14) + 1;
      boolean hasAlpha = br.readBits(1) > 0;
      br.readBits(3);
      BufferedImage image = new BufferedImage(iw, ih, 6);
      WritableRaster raster = image.getRaster();
      DataByteLittle db2 = new DataByteLittle(raw);
      WDecoder wDecoder = new WDecoder(db2);
      wDecoder.decodeImageStream(raster, true, iw, ih);
      return image;
   }

   private void createMacroBlocks() {
      this.macroBlocks = new MacroBlock[this.mbCols + 2][this.mbRows + 2];
      int xx = this.mbCols + 2;
      int yy = this.mbRows + 2;
      int[] cache16 = new int[16];

      for (int x = 0; x < xx; x++) {
         for (int y = 0; y < yy; y++) {
            this.macroBlocks[x][y] = new MacroBlock(x, y, cache16);
         }
      }
   }

   public Rectangle readDimension() {
      this.segmentQuants = new SegmentQuants();
      this.reader.moveTo(this.offset++);
      int c = this.reader.getU8();
      this.frameType = getBitAsInt(c, 0);
      getBitAsInt(c, 5);
      getBitAsInt(c, 6);
      getBitAsInt(c, 7);
      this.reader.getU8();
      this.reader.getU8();
      this.reader.getU8();
      this.reader.getU8();
      this.reader.getU8();
      c = this.reader.getU8();
      this.offset += 6;
      this.reader.moveTo(this.offset++);
      int var5 = this.reader.getU8();
      int var8 = c + (var5 << 8);
      this.width = var8 & 16383;
      this.reader.moveTo(this.offset++);
      c = this.reader.getU8();
      this.reader.moveTo(this.offset++);
      int var7 = this.reader.getU8();
      int var9 = c + (var7 << 8);
      this.height = var9 & 16383;
      return new Rectangle(this.width, this.height);
   }

   public void decodeFrame() {
      this.segmentQuants = new SegmentQuants();
      this.reader.moveTo(this.offset++);
      int c = this.reader.getU8();
      this.frameType = getBitAsInt(c, 0);
      if (this.frameType == 0) {
         int firstPartitionLengthInBytes = getBitAsInt(c, 5);
         firstPartitionLengthInBytes += getBitAsInt(c, 6) << 1;
         firstPartitionLengthInBytes += getBitAsInt(c, 7) << 2;
         c = this.reader.getU8();
         firstPartitionLengthInBytes += c << 3;
         c = this.reader.getU8();
         firstPartitionLengthInBytes += c << 11;
         this.reader.getU8();
         this.reader.getU8();
         this.reader.getU8();
         c = this.reader.getU8();
         this.offset += 6;
         this.reader.moveTo(this.offset++);
         int var12 = this.reader.getU8();
         int var19 = c + (var12 << 8);
         this.width = var19 & 16383;
         this.reader.moveTo(this.offset++);
         c = this.reader.getU8();
         this.reader.moveTo(this.offset++);
         int var14 = this.reader.getU8();
         int var20 = c + (var14 << 8);
         this.height = var20 & 16383;
         int tWidth = this.width;
         int tHeight = this.height;
         if ((tWidth & 15) != 0) {
            tWidth += 16 - (tWidth & 15);
         }

         if ((tHeight & 15) != 0) {
            tHeight += 16 - (tHeight & 15);
         }

         this.mbRows = tHeight >> 4;
         this.mbCols = tWidth >> 4;
         this.createMacroBlocks();
         BitDecoder bc = new BitDecoder(this.reader, this.offset);
         if (this.frameType == 0) {
            bc.getLiteral(2);
         }

         this.segmentationIsEnabled = bc.getBit();
         if (this.segmentationIsEnabled > 0) {
            this.mBlockMap = bc.getBit();
            int mBlockData = bc.getBit();
            if (mBlockData > 0) {
               this.processmBlockData(bc);
            }
         }

         int simpleFilter = bc.getBit();
         this.filterLevel = bc.getLiteral(6);
         this.sharpnessLevel = bc.getLiteral(3);
         this.modeRefLoopFilterDeltaEnabled = bc.getBit();
         if (this.modeRefLoopFilterDeltaEnabled > 0) {
            this.updateModeRefLoopFilter(bc);
         }

         this.filterType = this.filterLevel == 0 ? 0 : (simpleFilter > 0 ? 1 : 2);
         this.setupTokenDecoder(bc, firstPartitionLengthInBytes, this.offset);
         bc.seek();
         this.segmentQuants.parse(bc, this.segmentationIsEnabled == 1, this.macroBlockSegementAbsoluteDelta == 1);
         bc.getBit();
         if (this.frameType != 0) {
            bc.getBit();
         }

         this.decodeFrameStep2(bc);
      }
   }

   private void updateModeRefLoopFilter(BitDecoder bc) {
      int modeRefLoopFilterDeltaUpdate = bc.getBit();
      if (modeRefLoopFilterDeltaUpdate > 0) {
         for (int i = 0; i < 4; i++) {
            if (bc.getBit() > 0) {
               this.refLoopFilterDeltas[i] = bc.getLiteral(6);
               if (bc.getBit() > 0) {
                  this.refLoopFilterDeltas[i] = this.refLoopFilterDeltas[i] * -1;
               }
            }
         }

         for (int ix = 0; ix < 4; ix++) {
            if (bc.getBit() > 0) {
               this.modeLoopFilterDeltas[ix] = bc.getLiteral(6);
               if (bc.getBit() > 0) {
                  this.modeLoopFilterDeltas[ix] = this.modeLoopFilterDeltas[ix] * -1;
               }
            }
         }
      }
   }

   private void decodeFrameStep2(BitDecoder bc) {
      for (int i = 0; i < 4; i++) {
         for (int j = 0; j < 8; j++) {
            for (int k = 0; k < 3; k++) {
               for (int l = 0; l < 11; l++) {
                  if (bc.getProbBit(LookUp.PROB_COS[i][j][k][l]) > 0) {
                     this.coefProbs[i][j][k][l] = bc.getLiteral(8);
                  }
               }
            }
         }
      }

      this.macroBlockNoCoeffSkip = bc.getBit();
      if (this.frameType == 0) {
         this.readModes(bc);
      }

      int ibc = 0;
      int num_part = 1 << this.multiTokenPartition;

      for (int mb_row = 0; mb_row < this.mbRows; mb_row++) {
         if (num_part > 1) {
            this.tokenBoolDecoder = this.tokenBitDecoders.get(ibc);
            this.tokenBoolDecoder.seek();
            this.decodeMacroBlockRow(mb_row);
            if (++ibc == num_part) {
               ibc = 0;
            }
         } else {
            this.decodeMacroBlockRow(mb_row);
         }
      }

      if (this.filterType > 0 && this.filterLevel != 0) {
         filterFrame(this);
      }
   }

   private void processmBlockData(BitDecoder bc) {
      this.macroBlockSegementAbsoluteDelta = bc.getBit();

      for (int i = 0; i < 4; i++) {
         int value = 0;
         if (bc.getBit() > 0) {
            value = bc.getLiteral(LookUp.BITS_MACRO[0]);
            if (bc.getBit() > 0) {
               value = -value;
            }
         }

         this.segmentQuants.segQuants[i].index = value;
      }

      for (int i = 0; i < 4; i++) {
         int value = 0;
         if (bc.getBit() > 0) {
            value = bc.getLiteral(LookUp.BITS_MACRO[1]);
            if (bc.getBit() > 0) {
               value = -value;
            }
         }

         this.segmentQuants.segQuants[i].strength = value;
      }

      if (this.mBlockMap > 0) {
         this.macroBlockSegmentTreeProbs = new int[3];

         for (int i = 0; i < 3; i++) {
            int value;
            if (bc.getBit() > 0) {
               value = bc.getLiteral(8);
            } else {
               value = 255;
            }

            this.macroBlockSegmentTreeProbs[i] = value;
         }
      }
   }

   private void decodeMacroBlockRow(int mbRow) {
      for (int mbCol = 0; mbCol < this.mbCols; mbCol++) {
         MacroBlock mb = this.getMacroBlock(mbCol, mbRow);
         mb.decodeMacroBlock(this);
         mb.dequantMacroBlock(this);
      }
   }

   public SubBlock getTopRightSubBlock(SubBlock sb, SubBlock.Layer plane) {
      MacroBlock mb = sb.macroBlock;
      int x = mb.getSubblockX(sb);
      int y = mb.getSubblockY(sb);
      if (plane == SubBlock.Layer.Y1) {
         if (y == 0 && x < 3) {
            MacroBlock mb2 = this.getMacroBlock(mb.getX(), mb.getY() - 1);
            return mb2.getSubBlock(SubBlock.Layer.Y1, x + 1, 3);
         } else if (y == 0 && x == 3) {
            MacroBlock mb2 = this.getMacroBlock(mb.getX() + 1, mb.getY() - 1);
            SubBlock r = mb2.getSubBlock(SubBlock.Layer.Y1, 0, 3);
            if (mb2.getX() == this.mbCols) {
               int[][] dest = new int[4][4];

               for (int b = 0; b < 4; b++) {
                  for (int a = 0; a < 4; a++) {
                     if (mb2.getY() < 0) {
                        dest[a][b] = 127;
                     } else {
                        dest[a][b] = this.getMacroBlock(mb.getX(), mb.getY() - 1).getSubBlock(SubBlock.Layer.Y1, 3, 3).getDest()[3][3];
                     }
                  }
               }

               r = new SubBlock(mb2, null, null, SubBlock.Layer.Y1);
               r.dest = dest;
            }

            return r;
         } else if (y > 0 && x < 3) {
            return mb.getSubBlock(SubBlock.Layer.Y1, x + 1, y - 1);
         } else {
            SubBlock sb2 = mb.getSubBlock(sb.getLayer(), 3, 0);
            return this.getTopRightSubBlock(sb2, SubBlock.Layer.Y1);
         }
      } else {
         throw new IllegalArgumentException("bad input: getAboveRightSubBlock()");
      }
   }

   public SubBlock getTopSubBlock(SubBlock sb, SubBlock.Layer plane) {
      SubBlock r = sb.getAbove();
      if (r == null) {
         MacroBlock mb = sb.macroBlock;
         int x = mb.getSubblockX(sb);
         MacroBlock mb2 = this.getMacroBlock(mb.getX(), mb.getY() - 1);

         while (plane == SubBlock.Layer.Y2 && mb2.getYMode() == 4) {
            mb2 = this.getMacroBlock(mb2.getX(), mb2.getY() - 1);
         }

         r = mb2.getBottomSubBlock(x, sb.getLayer());
      }

      return r;
   }

   private static int getBitAsInt(int data, int bit) {
      int r = data & 1 << bit;
      return r > 0 ? 1 : 0;
   }

   public BufferedImage getBufferedImage() {
      BufferedImage bi = new BufferedImage(this.width, this.height, 1);
      int[] pixels = ((DataBufferInt)bi.getRaster().getDataBuffer()).getData();
      int p = 0;

      for (int y = 0; y < this.height; y++) {
         int j = (y >> 1 & 7) >> 2;
         int yPoint = y >> 1 & 3;

         for (int x = 0; x < this.width; x++) {
            int i = (x >> 1 & 7) >> 2;
            int xPoint = x >> 1 & 3;
            MacroBlock macroBlock = this.getMacroBlock(x >> 4, y >> 4);
            int yy = macroBlock.getSubBlock(SubBlock.Layer.Y1, (x & 15) >> 2, (y & 15) >> 2).getDest()[x & 3][y & 3];
            int u = macroBlock.getSubBlock(SubBlock.Layer.U, i, j).getDest()[xPoint][yPoint];
            int v = macroBlock.getSubBlock(SubBlock.Layer.V, i, j).getDest()[xPoint][yPoint];
            u -= 128;
            v -= 128;
            int a0 = 1192 * (yy - 16);
            int a1 = 1634 * v;
            int a2 = 832 * v;
            int a3 = 400 * u;
            int a4 = 2066 * u;
            int r = a0 + a1 >> 10;
            int g = a0 - a2 - a3 >> 10;
            int b = a0 + a4 >> 10;
            r = r > 255 ? 255 : (r < 0 ? 0 : r);
            g = g > 255 ? 255 : (g < 0 ? 0 : g);
            b = b > 255 ? 255 : (b < 0 ? 0 : b);
            pixels[p++] = r << 16 | g << 8 | b;
         }
      }

      return bi;
   }

   public int[][][][] getCoefProbs() {
      return this.coefProbs;
   }

   public SubBlock getLeftSubBlock(SubBlock sb, SubBlock.Layer plane) {
      SubBlock r = sb.getLeft();
      if (r == null) {
         MacroBlock mb = sb.macroBlock;
         int y = mb.getSubblockY(sb);
         MacroBlock mb2 = this.getMacroBlock(mb.getX() - 1, mb.getY());

         while (plane == SubBlock.Layer.Y2 && mb2.getYMode() == 4) {
            mb2 = this.getMacroBlock(mb2.getX() - 1, mb2.getY());
         }

         r = mb2.getRightSubBlock(y, sb.getLayer());
      }

      return r;
   }

   public MacroBlock getMacroBlock(int mbCol, int mbRow) {
      return this.macroBlocks[mbCol + 1][mbRow + 1];
   }

   public SegmentQuants getSegmentQuants() {
      return this.segmentQuants;
   }

   public BitDecoder getTokenBoolDecoder() {
      this.tokenBoolDecoder.seek();
      return this.tokenBoolDecoder;
   }

   private void readModes(BitDecoder bc) {
      int mb_row = -1;
      int prob_skip_false = 0;
      if (this.macroBlockNoCoeffSkip > 0) {
         prob_skip_false = bc.getLiteral(8);
      }

      while (++mb_row < this.mbRows) {
         int mb_col = -1;

         while (++mb_col < this.mbCols) {
            MacroBlock mb = this.getMacroBlock(mb_col, mb_row);
            if (this.segmentationIsEnabled > 0 && this.mBlockMap > 0) {
               mb.key = bc.getTree(LookUp.MB_SEG_TREE, this.macroBlockSegmentTreeProbs);
            }

            if (this.modeRefLoopFilterDeltaEnabled > 0) {
               int level = this.filterLevel;
               level += this.refLoopFilterDeltas[0];
               level = level < 0 ? 0 : (level > 63 ? 63 : level);
               mb.setFilterLevel(level);
            } else {
               mb.setFilterLevel(this.segmentQuants.segQuants[mb.key].strength);
            }

            int mb_skip_coeff;
            if (this.macroBlockNoCoeffSkip > 0) {
               mb_skip_coeff = bc.getProbBit(prob_skip_false);
            } else {
               mb_skip_coeff = 0;
            }

            mb.setSkipCoeff(mb_skip_coeff);
            int y_mode = readYMode(bc);
            mb.setYMode(y_mode);
            if (y_mode == 4) {
               this.handleB_PRED(bc, mb);
            } else {
               handleOther(mb, y_mode);
            }

            int mode = readUvMode(bc);
            mb.setUvMode(mode);
         }
      }
   }

   private static void handleOther(MacroBlock mb, int y_mode) {
      int BMode = switch (y_mode) {
         case 1 -> 2;
         case 2 -> 3;
         case 3 -> 1;
         default -> 0;
      };

      for (int x = 0; x < 4; x++) {
         for (int y = 0; y < 4; y++) {
            SubBlock sb = mb.getYSubBlock(x, y);
            sb.setMode(BMode);
         }
      }
   }

   private void handleB_PRED(BitDecoder bc, MacroBlock mb) {
      for (int i = 0; i < 4; i++) {
         for (int j = 0; j < 4; j++) {
            SubBlock sb = mb.getYSubBlock(j, i);
            SubBlock top = this.getTopSubBlock(sb, SubBlock.Layer.Y1);
            SubBlock left = this.getLeftSubBlock(sb, SubBlock.Layer.Y1);
            int mode = readSubBlockMode(bc, top.mode, left.mode);
            sb.setMode(mode);
         }
      }

      if (this.modeRefLoopFilterDeltaEnabled > 0) {
         int level = mb.getFilterLevel();
         level += this.modeLoopFilterDeltas[0];
         level = level < 0 ? 0 : (level > 63 ? 63 : level);
         mb.setFilterLevel(level);
      }
   }

   private int readPartitionSize(int l) {
      this.reader.moveTo(l);
      return this.reader.getU8() + (this.reader.getU8() << 8) + (this.reader.getU8() << 16);
   }

   private static int readSubBlockMode(BitDecoder bc, int A, int L) {
      return bc.getTree(LookUp.SUBBLOCK_MODE_TREE, LookUp.FRAMES_SUBBLOCK[A][L]);
   }

   private static int readUvMode(BitDecoder bc) {
      return bc.getTree(LookUp.UV_MODE_TREE, LookUp.UV_FRAME_PROB);
   }

   private static int readYMode(BitDecoder bc) {
      return bc.getTree(LookUp.Y_FRAME_TREE, LookUp.FRAME_YMODE_PROB);
   }

   private void setupTokenDecoder(BitDecoder bc, int first_partition_length_in_bytes, int offset) {
      int partStart = offset + first_partition_length_in_bytes;
      int partition = partStart;
      this.multiTokenPartition = bc.getLiteral(2);
      int num_part = 1 << this.multiTokenPartition;
      if (num_part > 1) {
         partition = partStart + 3 * (num_part - 1);
      }

      for (int i = 0; i < num_part; i++) {
         int partSize;
         if (i < num_part - 1) {
            partSize = this.readPartitionSize(partStart + i * 3);
            bc.seek();
         } else {
            partSize = this.reader.getLength() - partition;
         }

         this.tokenBitDecoders.add(new BitDecoder(this.reader, partition));
         partition += partSize;
      }

      this.tokenBoolDecoder = this.tokenBitDecoders.get(0);
   }

   private static int common_adjust(boolean use_outer_taps, Segment seg) {
      int p1 = getSigned(seg.P1);
      int p0 = getSigned(seg.P0);
      int q0 = getSigned(seg.Q0);
      int q1 = getSigned(seg.Q1);
      int a = sClamp((use_outer_taps ? sClamp(p1 - q1) : 0) + 3 * (q0 - p0));
      int b = sClamp(a + 3) >> 3;
      a = sClamp(a + 4) >> 3;
      seg.Q0 = getUnsigned(q0 - a);
      seg.P0 = getUnsigned(p0 + b);
      return a;
   }

   private static boolean doY(int I, int E, int p3, int p2, int p1, int p0, int q0, int q1, int q2, int q3) {
      return abs(p0 - q0) * 2 + abs(p1 - q1) / 2 <= E
         && abs(p3 - p2) <= I
         && abs(p2 - p1) <= I
         && abs(p1 - p0) <= I
         && abs(q3 - q2) <= I
         && abs(q2 - q1) <= I
         && abs(q1 - q0) <= I;
   }

   private static Segment getSegH(SubBlock rsb, SubBlock lsb, int a) {
      Segment seg = new Segment();
      int[][] rdest = rsb.getDest();
      int[][] ldest = lsb.getDest();
      seg.P0 = ldest[3][a];
      seg.P1 = ldest[2][a];
      seg.P2 = ldest[1][a];
      seg.P3 = ldest[0][a];
      seg.Q0 = rdest[0][a];
      seg.Q1 = rdest[1][a];
      seg.Q2 = rdest[2][a];
      seg.Q3 = rdest[3][a];
      return seg;
   }

   private static Segment getSegV(SubBlock bsb, SubBlock tsb, int a) {
      Segment seg = new Segment();
      int[][] bdest = bsb.getDest();
      int[][] tdest = tsb.getDest();
      seg.P0 = tdest[a][3];
      seg.P1 = tdest[a][2];
      seg.P2 = tdest[a][1];
      seg.P3 = tdest[a][0];
      seg.Q0 = bdest[a][0];
      seg.Q1 = bdest[a][1];
      seg.Q2 = bdest[a][2];
      seg.Q3 = bdest[a][3];
      return seg;
   }

   private static boolean hev(int threshold, int p1, int p0, int q0, int q1) {
      return abs(p1 - p0) > threshold || abs(q1 - q0) > threshold;
   }

   private static void filterFrame(Frame frame) {
      if (frame.filterType == 2) {
         filterUV(frame);
         filterY(frame);
      } else if (frame.filterType == 1) {
         filterNorm(frame);
      }
   }

   private static void filterNorm(Frame frame) {
      for (int y = 0; y < frame.mbRows; y++) {
         for (int x = 0; x < frame.mbCols; x++) {
            MacroBlock rmb = frame.getMacroBlock(x, y);
            MacroBlock bmb = frame.getMacroBlock(x, y);
            int loop_filter_level = rmb.getFilterLevel();
            if (loop_filter_level != 0) {
               int iLimit = rmb.getFilterLevel();
               int sharpnessLevel = frame.sharpnessLevel;
               if (sharpnessLevel > 0) {
                  iLimit >>= sharpnessLevel > 4 ? 2 : 1;
                  if (iLimit > 9 - sharpnessLevel) {
                     iLimit = 9 - sharpnessLevel;
                  }
               }

               if (iLimit == 0) {
                  iLimit = 1;
               }

               int sub_bedge_limit = (loop_filter_level << 1) + iLimit;
               if (sub_bedge_limit < 1) {
                  sub_bedge_limit = 1;
               }

               if (x > 0) {
                  setSegHIfXPositive(frame, x, y, rmb, sub_bedge_limit);
               }

               if (!rmb.isSkip_inner_lf()) {
                  setSegHIfNoLoopSkip(rmb, sub_bedge_limit);
               }

               if (y > 0) {
                  setSegVIfYPositive(frame, x, y, bmb, sub_bedge_limit);
               }

               if (!rmb.isSkip_inner_lf()) {
                  setSegVIfNoLoopSkip(bmb, sub_bedge_limit);
               }
            }
         }
      }
   }

   private static void setSegVIfNoLoopSkip(MacroBlock bmb, int sub_bedge_limit) {
      for (int a = 1; a < 4; a++) {
         for (int b = 0; b < 4; b++) {
            SubBlock tsb = bmb.getSubBlock(SubBlock.Layer.Y1, b, a - 1);
            SubBlock bsb = bmb.getSubBlock(SubBlock.Layer.Y1, b, a);

            for (int c = 0; c < 4; c++) {
               Segment seg = getSegV(bsb, tsb, c);
               normalizeSegment(sub_bedge_limit, seg);
               setSegV(bsb, tsb, seg, c);
            }
         }
      }
   }

   private static void setSegVIfYPositive(Frame frame, int x, int y, MacroBlock bmb, int sub_bedge_limit) {
      int mbedge_limit = sub_bedge_limit + 4;
      MacroBlock tmb = frame.getMacroBlock(x, y - 1);

      for (int b = 0; b < 4; b++) {
         SubBlock tsb = tmb.getSubBlock(SubBlock.Layer.Y1, b, 3);
         SubBlock bsb = bmb.getSubBlock(SubBlock.Layer.Y1, b, 0);

         for (int a = 0; a < 4; a++) {
            Segment seg = getSegV(bsb, tsb, a);
            normalizeSegment(mbedge_limit, seg);
            setSegV(bsb, tsb, seg, a);
         }
      }
   }

   private static void setSegHIfNoLoopSkip(MacroBlock rmb, int sub_bedge_limit) {
      for (int a = 1; a < 4; a++) {
         for (int b = 0; b < 4; b++) {
            SubBlock lsb = rmb.getSubBlock(SubBlock.Layer.Y1, a - 1, b);
            SubBlock rsb = rmb.getSubBlock(SubBlock.Layer.Y1, a, b);

            for (int c = 0; c < 4; c++) {
               Segment seg = getSegH(rsb, lsb, c);
               normalizeSegment(sub_bedge_limit, seg);
               setSegH(rsb, lsb, seg, c);
            }
         }
      }
   }

   private static void setSegHIfXPositive(Frame frame, int x, int y, MacroBlock rmb, int sub_bedge_limit) {
      int mbedge_limit = sub_bedge_limit + 4;
      MacroBlock lmb = frame.getMacroBlock(x - 1, y);

      for (int b = 0; b < 4; b++) {
         SubBlock rsb = rmb.getSubBlock(SubBlock.Layer.Y1, 0, b);
         SubBlock lsb = lmb.getSubBlock(SubBlock.Layer.Y1, 3, b);

         for (int a = 0; a < 4; a++) {
            Segment seg = getSegH(rsb, lsb, a);
            normalizeSegment(mbedge_limit, seg);
            setSegH(rsb, lsb, seg, a);
         }
      }
   }

   private static void filterUV(Frame frame) {
      for (int y = 0; y < frame.mbRows; y++) {
         for (int x = 0; x < frame.mbCols; x++) {
            MacroBlock rmb = frame.getMacroBlock(x, y);
            int level = rmb.getFilterLevel();
            if (level != 0) {
               if (x > 0) {
                  filterFirst(frame, x, y, rmb, level);
               }

               if (!rmb.isSkip_inner_lf()) {
                  filterSecond(frame, rmb, level);
               }

               if (y > 0) {
                  filterThird(frame, x, y, rmb, level);
               }

               if (!rmb.isSkip_inner_lf()) {
                  filterFourth(frame, x, y, rmb, level);
               }
            }
         }
      }
   }

   private static void filterFirst(Frame frame, int x, int y, MacroBlock rmb, int level) {
      MacroBlock lmb = frame.getMacroBlock(x - 1, y);
      int sLevel = frame.sharpnessLevel;
      int iLimit = getiLimit(rmb, sLevel);
      int limitMBE = (level + 2 << 1) + iLimit;
      int hev_threshold = getHev_threshold(frame, level);

      for (int b = 0; b < 2; b++) {
         SubBlock rsbU = rmb.getSubBlock(SubBlock.Layer.U, 0, b);
         SubBlock lsbU = lmb.getSubBlock(SubBlock.Layer.U, 1, b);
         SubBlock rsbV = rmb.getSubBlock(SubBlock.Layer.V, 0, b);
         SubBlock lsbV = lmb.getSubBlock(SubBlock.Layer.V, 1, b);

         for (int a = 0; a < 4; a++) {
            Segment seg = getSegH(rsbU, lsbU, a);
            filterMB(hev_threshold, iLimit, limitMBE, seg);
            setSegH(rsbU, lsbU, seg, a);
            seg = getSegH(rsbV, lsbV, a);
            filterMB(hev_threshold, iLimit, limitMBE, seg);
            setSegH(rsbV, lsbV, seg, a);
         }
      }
   }

   private static void filterSecond(Frame frame, MacroBlock rmb, int level) {
      int hev_threshold = getHev_threshold(frame, level);
      int sLevel = frame.sharpnessLevel;
      int iLimit = getiLimit(rmb, sLevel);
      int limitSBE = (level << 1) + iLimit;

      for (int b = 0; b < 2; b++) {
         SubBlock lsbU = rmb.getSubBlock(SubBlock.Layer.U, 0, b);
         SubBlock rsbU = rmb.getSubBlock(SubBlock.Layer.U, 1, b);
         SubBlock lsbV = rmb.getSubBlock(SubBlock.Layer.V, 0, b);
         SubBlock rsbV = rmb.getSubBlock(SubBlock.Layer.V, 1, b);

         for (int c = 0; c < 4; c++) {
            Segment seg = getSegH(rsbU, lsbU, c);
            filterSB(hev_threshold, iLimit, limitSBE, seg);
            setSegH(rsbU, lsbU, seg, c);
            seg = getSegH(rsbV, lsbV, c);
            filterSB(hev_threshold, iLimit, limitSBE, seg);
            setSegH(rsbV, lsbV, seg, c);
         }
      }
   }

   private static void filterThird(Frame frame, int x, int y, MacroBlock rmb, int level) {
      MacroBlock tmb = frame.getMacroBlock(x, y - 1);
      MacroBlock bmb = frame.getMacroBlock(x, y);
      int sLevel = frame.sharpnessLevel;
      int iLimit = getiLimit(rmb, sLevel);
      int limitMBE = (level + 2 << 1) + iLimit;
      int hev_threshold = getHev_threshold(frame, level);

      for (int b = 0; b < 2; b++) {
         SubBlock tsbU = tmb.getSubBlock(SubBlock.Layer.U, b, 1);
         SubBlock bsbU = bmb.getSubBlock(SubBlock.Layer.U, b, 0);
         SubBlock tsbV = tmb.getSubBlock(SubBlock.Layer.V, b, 1);
         SubBlock bsbV = bmb.getSubBlock(SubBlock.Layer.V, b, 0);

         for (int a = 0; a < 4; a++) {
            Segment seg = getSegV(bsbU, tsbU, a);
            filterMB(hev_threshold, iLimit, limitMBE, seg);
            setSegV(bsbU, tsbU, seg, a);
            seg = getSegV(bsbV, tsbV, a);
            filterMB(hev_threshold, iLimit, limitMBE, seg);
            setSegV(bsbV, tsbV, seg, a);
         }
      }
   }

   private static void filterFourth(Frame frame, int x, int y, MacroBlock rmb, int level) {
      MacroBlock bmb = frame.getMacroBlock(x, y);
      int sLevel = frame.sharpnessLevel;
      int hev_threshold = getHev_threshold(frame, level);
      int iLimit = getiLimit(rmb, sLevel);
      int limitSBE = (level << 1) + iLimit;

      for (int a = 1; a < 2; a++) {
         for (int b = 0; b < 2; b++) {
            SubBlock tsbU = bmb.getSubBlock(SubBlock.Layer.U, b, 0);
            SubBlock bsbU = bmb.getSubBlock(SubBlock.Layer.U, b, a);
            SubBlock tsbV = bmb.getSubBlock(SubBlock.Layer.V, b, 0);
            SubBlock bsbV = bmb.getSubBlock(SubBlock.Layer.V, b, a);

            for (int c = 0; c < 4; c++) {
               Segment seg = getSegV(bsbU, tsbU, c);
               filterSB(hev_threshold, iLimit, limitSBE, seg);
               setSegV(bsbU, tsbU, seg, c);
               seg = getSegV(bsbV, tsbV, c);
               filterSB(hev_threshold, iLimit, limitSBE, seg);
               setSegV(bsbV, tsbV, seg, c);
            }
         }
      }
   }

   private static int getiLimit(MacroBlock rmb, int sLevel) {
      int iLimit = rmb.getFilterLevel();
      if (sLevel > 0) {
         iLimit >>= sLevel > 4 ? 2 : 1;
         if (iLimit > 9 - sLevel) {
            iLimit = 9 - sLevel;
         }
      }

      if (iLimit == 0) {
         iLimit = 1;
      }

      return iLimit;
   }

   private static void filterY(Frame frame) {
      for (int y = 0; y < frame.mbRows; y++) {
         for (int x = 0; x < frame.mbCols; x++) {
            MacroBlock rmb = frame.getMacroBlock(x, y);
            int level = rmb.getFilterLevel();
            if (level != 0) {
               if (x > 0) {
                  filterYFirst(frame, x, y, rmb, level);
               }

               if (!rmb.isSkip_inner_lf()) {
                  filterYSecond(frame, rmb, level);
               }

               if (y > 0) {
                  filterYThird(frame, x, y, rmb, level);
               }

               if (!rmb.isSkip_inner_lf()) {
                  filterYFourth(frame, x, y, rmb, level);
               }
            }
         }
      }
   }

   private static void filterYFirst(Frame frame, int x, int y, MacroBlock rmb, int level) {
      int hev_threshold = getHev_threshold(frame, level);
      int sharpnessLevel = frame.sharpnessLevel;
      int iLimit = getiLimit(rmb, sharpnessLevel);
      MacroBlock lmb = frame.getMacroBlock(x - 1, y);
      int mbedge_limit = (level + 2 << 1) + iLimit;

      for (int b = 0; b < 4; b++) {
         SubBlock rsb = rmb.getSubBlock(SubBlock.Layer.Y1, 0, b);
         SubBlock lsb = lmb.getSubBlock(SubBlock.Layer.Y1, 3, b);

         for (int a = 0; a < 4; a++) {
            Segment seg = getSegH(rsb, lsb, a);
            filterMB(hev_threshold, iLimit, mbedge_limit, seg);
            setSegH(rsb, lsb, seg, a);
         }
      }
   }

   private static void filterYSecond(Frame frame, MacroBlock rmb, int level) {
      int hev_threshold = getHev_threshold(frame, level);
      int sharpnessLevel = frame.sharpnessLevel;
      int iLimit = getiLimit(rmb, sharpnessLevel);
      int sub_bedge_limit = (level << 1) + iLimit;

      for (int a = 1; a < 4; a++) {
         for (int b = 0; b < 4; b++) {
            SubBlock lsb = rmb.getSubBlock(SubBlock.Layer.Y1, a - 1, b);
            SubBlock rsb = rmb.getSubBlock(SubBlock.Layer.Y1, a, b);

            for (int c = 0; c < 4; c++) {
               Segment seg = getSegH(rsb, lsb, c);
               filterSB(hev_threshold, iLimit, sub_bedge_limit, seg);
               setSegH(rsb, lsb, seg, c);
            }
         }
      }
   }

   private static void filterYThird(Frame frame, int x, int y, MacroBlock rmb, int level) {
      MacroBlock tmb = frame.getMacroBlock(x, y - 1);
      MacroBlock bmb = frame.getMacroBlock(x, y);
      int sharpnessLevel = frame.sharpnessLevel;
      int hev_threshold = getHev_threshold(frame, level);
      int iLimit = getiLimit(rmb, sharpnessLevel);
      int mbedge_limit = (level + 2 << 1) + iLimit;

      for (int b = 0; b < 4; b++) {
         SubBlock tsb = tmb.getSubBlock(SubBlock.Layer.Y1, b, 3);
         SubBlock bsb = bmb.getSubBlock(SubBlock.Layer.Y1, b, 0);

         for (int a = 0; a < 4; a++) {
            Segment seg = getSegV(bsb, tsb, a);
            filterMB(hev_threshold, iLimit, mbedge_limit, seg);
            setSegV(bsb, tsb, seg, a);
         }
      }
   }

   private static void filterYFourth(Frame frame, int x, int y, MacroBlock rmb, int level) {
      MacroBlock bmb = frame.getMacroBlock(x, y);
      int sharpnessLevel = frame.sharpnessLevel;
      int hev_threshold = getHev_threshold(frame, level);
      int iLimit = getiLimit(rmb, sharpnessLevel);
      int sub_bedge_limit = (level << 1) + iLimit;

      for (int a = 1; a < 4; a++) {
         for (int b = 0; b < 4; b++) {
            SubBlock tsb = bmb.getSubBlock(SubBlock.Layer.Y1, b, a - 1);
            SubBlock bsb = bmb.getSubBlock(SubBlock.Layer.Y1, b, a);

            for (int c = 0; c < 4; c++) {
               Segment seg = getSegV(bsb, tsb, c);
               filterSB(hev_threshold, iLimit, sub_bedge_limit, seg);
               setSegV(bsb, tsb, seg, c);
            }
         }
      }
   }

   private static int getHev_threshold(Frame frame, int level) {
      int hev_threshold = 0;
      if (frame.frameType == 0) {
         if (level >= 40) {
            hev_threshold = 2;
         } else if (level >= 15) {
            hev_threshold = 1;
         }
      } else if (level >= 40) {
         hev_threshold = 3;
      } else if (level >= 20) {
         hev_threshold = 2;
      } else if (level >= 15) {
         hev_threshold = 1;
      }

      return hev_threshold;
   }

   private static void filterMB(int hev_threshold, int iLimit, int eLimit, Segment seg) {
      int p3 = getSigned(seg.P3);
      int p2 = getSigned(seg.P2);
      int p1 = getSigned(seg.P1);
      int p0 = getSigned(seg.P0);
      int q0 = getSigned(seg.Q0);
      int q1 = getSigned(seg.Q1);
      int q2 = getSigned(seg.Q2);
      int q3 = getSigned(seg.Q3);
      if (doY(iLimit, eLimit, q3, q2, q1, q0, p0, p1, p2, p3)) {
         if (!hev(hev_threshold, p1, p0, q0, q1)) {
            int w = sClamp(sClamp(p1 - q1) + 3 * (q0 - p0));
            int a = 27 * w + 63 >> 7;
            seg.Q0 = getUnsigned(q0 - a);
            seg.P0 = getUnsigned(p0 + a);
            a = 18 * w + 63 >> 7;
            seg.Q1 = getUnsigned(q1 - a);
            seg.P1 = getUnsigned(p1 + a);
            a = 9 * w + 63 >> 7;
            seg.Q2 = getUnsigned(q2 - a);
            seg.P2 = getUnsigned(p2 + a);
         } else {
            common_adjust(true, seg);
         }
      }
   }

   private static void setSegH(SubBlock rsb, SubBlock lsb, Segment seg, int a) {
      int[][] rdest = rsb.getDest();
      int[][] ldest = lsb.getDest();
      ldest[3][a] = seg.P0;
      ldest[2][a] = seg.P1;
      ldest[1][a] = seg.P2;
      ldest[0][a] = seg.P3;
      rdest[0][a] = seg.Q0;
      rdest[1][a] = seg.Q1;
      rdest[2][a] = seg.Q2;
      rdest[3][a] = seg.Q3;
   }

   private static void setSegV(SubBlock bsb, SubBlock tsb, Segment seg, int a) {
      int[][] bdest = bsb.getDest();
      int[][] tdest = tsb.getDest();
      tdest[a][3] = seg.P0;
      tdest[a][2] = seg.P1;
      tdest[a][1] = seg.P2;
      tdest[a][0] = seg.P3;
      bdest[a][0] = seg.Q0;
      bdest[a][1] = seg.Q1;
      bdest[a][2] = seg.Q2;
      bdest[a][3] = seg.Q3;
   }

   private static void normalizeSegment(int eLimit, Segment seg) {
      if (abs(seg.P0 - seg.Q0) * 2 + abs(seg.P1 - seg.Q1) / 2 <= eLimit) {
         common_adjust(true, seg);
      }
   }

   private static void filterSB(int hev_threshold, int interior_limit, int edge_limit, Segment seg) {
      int p3 = getSigned(seg.P3);
      int p2 = getSigned(seg.P2);
      int p1 = getSigned(seg.P1);
      int p0 = getSigned(seg.P0);
      int q0 = getSigned(seg.Q0);
      int q1 = getSigned(seg.Q1);
      int q2 = getSigned(seg.Q2);
      int q3 = getSigned(seg.Q3);
      if (doY(interior_limit, edge_limit, q3, q2, q1, q0, p0, p1, p2, p3)) {
         boolean hv = hev(hev_threshold, p1, p0, q0, q1);
         int a = common_adjust(hv, seg) + 1 >> 1;
         if (!hv) {
            seg.Q1 = getUnsigned(q1 - a);
            seg.P1 = getUnsigned(p1 + a);
         }
      }
   }

   private static int getSigned(int v) {
      return v - 128;
   }

   private static int getUnsigned(int v) {
      return sClamp(v) + 128;
   }

   private static int abs(int v) {
      return v < 0 ? -v : v;
   }

   private static int sClamp(int v) {
      int r = v;
      if (v < -128) {
         r = -128;
      }

      if (v > 127) {
         r = 127;
      }

      return r;
   }

   private static final class Segment {
      int P0;
      int P1;
      int P2;
      int P3;
      int Q0;
      int Q1;
      int Q2;
      int Q3;
   }
}
