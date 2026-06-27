package rip.ysm.imagestream.webp.data;

class MacroBlock {
   private int filterLevel;
   int key;
   private int skipCoeff;
   private boolean innerLoopSkip;
   private final SubBlock[][] uSubBlocks;
   private int uvMode;
   private final SubBlock[][] vSubBlocks;
   private final int x;
   private final int y;
   private final SubBlock y2SubBlock;
   private int yMode;
   private final SubBlock[][] ySubBlocks;
   final int[] cache16;

   MacroBlock(int x, int y, int[] cache16) {
      this.x = x - 1;
      this.y = y - 1;
      this.cache16 = cache16;
      this.ySubBlocks = new SubBlock[4][4];
      this.uSubBlocks = new SubBlock[2][2];
      this.vSubBlocks = new SubBlock[2][2];

      for (int i = 0; i < 4; i++) {
         for (int j = 0; j < 4; j++) {
            SubBlock left = null;
            SubBlock above = null;
            if (j > 0) {
               left = this.ySubBlocks[j - 1][i];
            }

            if (i > 0) {
               above = this.ySubBlocks[j][i - 1];
            }

            this.ySubBlocks[j][i] = new SubBlock(this, above, left, SubBlock.Layer.Y1);
         }
      }

      for (int i = 0; i < 2; i++) {
         for (int j = 0; j < 2; j++) {
            SubBlock leftx = null;
            SubBlock abovex = null;
            if (j > 0) {
               leftx = this.uSubBlocks[j - 1][i];
            }

            if (i > 0) {
               abovex = this.uSubBlocks[j][i - 1];
            }

            this.uSubBlocks[j][i] = new SubBlock(this, abovex, leftx, SubBlock.Layer.U);
         }
      }

      for (int var13 = 0; var13 < 2; var13++) {
         for (int j = 0; j < 2; j++) {
            SubBlock leftxx = null;
            SubBlock abovexx = null;
            if (j > 0) {
               leftxx = this.vSubBlocks[j - 1][var13];
            }

            if (var13 > 0) {
               abovexx = this.vSubBlocks[j][var13 - 1];
            }

            this.vSubBlocks[j][var13] = new SubBlock(this, abovexx, leftxx, SubBlock.Layer.V);
         }
      }

      this.y2SubBlock = new SubBlock(this, null, null, SubBlock.Layer.Y2);
   }

   void decodeMacroBlock(Frame frame) {
      if (this.skipCoeff > 0) {
         if (this.yMode != 4) {
            this.innerLoopSkip = true;
         }
      } else {
         this.decodeMacroBlockTokens(frame, this.yMode != 4);
      }
   }

   private void decodeMacroBlockTokens(Frame frame, boolean withY2) {
      this.innerLoopSkip = false;
      if (withY2) {
         this.decodePlaneTokens(frame, 1, SubBlock.Layer.Y2, false);
      }

      this.decodePlaneTokens(frame, 4, SubBlock.Layer.Y1, withY2);
      this.decodePlaneTokens(frame, 2, SubBlock.Layer.U, false);
      this.decodePlaneTokens(frame, 2, SubBlock.Layer.V, false);
   }

   private void decodePlaneTokens(Frame frame, int dimentions, SubBlock.Layer plane, boolean withY2) {
      MacroBlock mb = this;

      for (int i = 0; i < dimentions; i++) {
         for (int j = 0; j < dimentions; j++) {
            int L = 0;
            int A = 0;
            int lc = 0;
            SubBlock sb = mb.getSubBlock(plane, j, i);
            SubBlock left = frame.getLeftSubBlock(sb, plane);
            SubBlock above = frame.getTopSubBlock(sb, plane);
            if (left.hasNoZeroToken()) {
               L = 1;
            }

            lc += L;
            if (above.hasNoZeroToken()) {
               A = 1;
            }

            lc += A;
            sb.decode(frame.getTokenBoolDecoder(), frame.getCoefProbs(), lc, SubBlock.layerToType(plane, withY2), withY2);
            sb.hasNoZeroToken();
         }
      }
   }

   void dequantMacroBlock(Frame frame) {
      MacroBlock mb = this;
      if (this.yMode != 4) {
         SubBlock sb = this.y2SubBlock;
         int acQValue = frame.getSegmentQuants().segQuants[this.key].y2ac;
         int dcQValue = frame.getSegmentQuants().segQuants[this.key].y2dc;
         int[] input = getInput(sb, acQValue, dcQValue);
         sb.diff = Transform.walsh(input, this.cache16);

         for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
               SubBlock ysb = mb.getYSubBlock(i, j);
               ysb.dequantSubBlock(frame, sb.getDiff()[i][j]);
            }
         }

         mb.predictY(frame);
         mb.predictUV(frame);

         for (int i = 0; i < 2; i++) {
            for (int var14 = 0; var14 < 2; var14++) {
               SubBlock uvsb = mb.getUSubBlock(var14, i);
               uvsb.dequantSubBlock(frame, null);
               uvsb = mb.getVSubBlock(i, var14);
               uvsb.dequantSubBlock(frame, null);
            }
         }

         mb.recon_mb();
      } else {
         for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
               SubBlock sb = mb.getYSubBlock(i, j);
               sb.dequantSubBlock(frame, null);
               sb.predict(frame);
               sb.reconstruct();
            }
         }

         mb.predictUV(frame);

         for (int i = 0; i < 2; i++) {
            for (int var16 = 0; var16 < 2; var16++) {
               SubBlock sb = mb.getUSubBlock(var16, i);
               sb.dequantSubBlock(frame, null);
               sb.reconstruct();
            }
         }

         for (int var13 = 0; var13 < 2; var13++) {
            for (int var17 = 0; var17 < 2; var17++) {
               SubBlock sb = mb.getVSubBlock(var17, var13);
               sb.dequantSubBlock(frame, null);
               sb.reconstruct();
            }
         }
      }
   }

   private static int[] getInput(SubBlock sb, int acQValue, int dcQValue) {
      int[] input = new int[16];
      input[0] = sb.getTokens()[0] * dcQValue;

      for (int x = 1; x < 16; x++) {
         input[x] = sb.getTokens()[x] * acQValue;
      }

      return input;
   }

   SubBlock getBottomSubBlock(int x, SubBlock.Layer plane) {
      return switch (plane) {
         case Y1 -> this.ySubBlocks[x][3];
         case U -> this.uSubBlocks[x][1];
         case V -> this.vSubBlocks[x][1];
         case Y2 -> this.y2SubBlock;
      };
   }

   int getFilterLevel() {
      return this.filterLevel;
   }

   SubBlock getRightSubBlock(int y, SubBlock.Layer plane) {
      if (null != plane) {
         switch (plane) {
            case Y1:
               return this.ySubBlocks[3][y];
            case U:
               return this.uSubBlocks[1][y];
            case V:
               return this.vSubBlocks[1][y];
            case Y2:
               return this.y2SubBlock;
         }
      }

      return null;
   }

   SubBlock getSubBlock(SubBlock.Layer plane, int i, int j) {
      return switch (plane) {
         case Y1 -> this.getYSubBlock(i, j);
         case U -> this.getUSubBlock(i, j);
         case V -> this.getVSubBlock(i, j);
         case Y2 -> this.y2SubBlock;
      };
   }

   int getSubblockX(SubBlock sb) {
      if (null != sb.getLayer()) {
         switch (sb.getLayer()) {
            case Y1:
               for (int y = 0; y < 4; y++) {
                  for (int xxx = 0; xxx < 4; xxx++) {
                     if (this.ySubBlocks[xxx][y] == sb) {
                        return xxx;
                     }
                  }
               }
               break;
            case U:
               for (int y = 0; y < 2; y++) {
                  for (int xx = 0; xx < 2; xx++) {
                     if (this.uSubBlocks[xx][y] == sb) {
                        return xx;
                     }
                  }
               }
               break;
            case V:
               for (int y = 0; y < 2; y++) {
                  for (int x = 0; x < 2; x++) {
                     if (this.vSubBlocks[x][y] == sb) {
                        return x;
                     }
                  }
               }
               break;
            case Y2:
               return 0;
         }
      }

      return -100;
   }

   int getSubblockY(SubBlock sb) {
      if (null != sb.getLayer()) {
         switch (sb.getLayer()) {
            case Y1:
               for (int y = 0; y < 4; y++) {
                  for (int xxx = 0; xxx < 4; xxx++) {
                     if (this.ySubBlocks[xxx][y] == sb) {
                        return y;
                     }
                  }
               }
               break;
            case U:
               for (int y = 0; y < 2; y++) {
                  for (int xx = 0; xx < 2; xx++) {
                     if (this.uSubBlocks[xx][y] == sb) {
                        return y;
                     }
                  }
               }
               break;
            case V:
               for (int y = 0; y < 2; y++) {
                  for (int x = 0; x < 2; x++) {
                     if (this.vSubBlocks[x][y] == sb) {
                        return y;
                     }
                  }
               }
               break;
            case Y2:
               return 0;
         }
      }

      return -100;
   }

   private SubBlock getUSubBlock(int i, int j) {
      return this.uSubBlocks[i][j];
   }

   private SubBlock getVSubBlock(int i, int j) {
      return this.vSubBlocks[i][j];
   }

   int getX() {
      return this.x;
   }

   int getY() {
      return this.y;
   }

   int getYMode() {
      return this.yMode;
   }

   SubBlock getYSubBlock(int i, int j) {
      return this.ySubBlocks[i][j];
   }

   boolean isSkip_inner_lf() {
      return this.innerLoopSkip;
   }

   private void predictUV(Frame frame) {
      MacroBlock aboveMb = frame.getMacroBlock(this.x, this.y - 1);
      MacroBlock leftMb = frame.getMacroBlock(this.x - 1, this.y);
      switch (this.uvMode) {
         case 0:
            this.doDCPredict(aboveMb, leftMb);
            break;
         case 1:
            this.doVPredict(aboveMb);
            break;
         case 2:
            this.doHPredict(leftMb);
            break;
         case 3:
            this.doTMPredict(aboveMb, leftMb, frame);
      }
   }

   private void doDCPredict(MacroBlock aboveMb, MacroBlock leftMb) {
      boolean up_available = false;
      boolean left_available = false;
      int Uaverage = 0;
      int Vaverage = 0;
      if (this.x > 0) {
         left_available = true;
      }

      if (this.y > 0) {
         up_available = true;
      }

      int expected_udc;
      int expected_vdc;
      if (!up_available && !left_available) {
         expected_udc = 128;
         expected_vdc = 128;
      } else {
         if (up_available) {
            for (int j = 0; j < 2; j++) {
               SubBlock usb = aboveMb.getUSubBlock(j, 1);
               SubBlock vsb = aboveMb.getVSubBlock(j, 1);

               for (int i = 0; i < 4; i++) {
                  Uaverage += usb.getDest()[i][3];
                  Vaverage += vsb.getDest()[i][3];
               }
            }
         }

         if (left_available) {
            for (int j = 0; j < 2; j++) {
               SubBlock usb = leftMb.getUSubBlock(1, j);
               SubBlock vsb = leftMb.getVSubBlock(1, j);

               for (int i = 0; i < 4; i++) {
                  Uaverage += usb.getDest()[3][i];
                  Vaverage += vsb.getDest()[3][i];
               }
            }
         }

         int shift = 2;
         if (up_available) {
            shift++;
         }

         if (left_available) {
            shift++;
         }

         expected_udc = Uaverage + (1 << shift - 1) >> shift;
         expected_vdc = Vaverage + (1 << shift - 1) >> shift;
      }

      int[][] ufill = new int[4][4];

      for (int y = 0; y < 4; y++) {
         for (int x = 0; x < 4; x++) {
            ufill[x][y] = expected_udc;
         }
      }

      int[][] vfill = new int[4][4];

      for (int y = 0; y < 4; y++) {
         for (int x = 0; x < 4; x++) {
            vfill[x][y] = expected_vdc;
         }
      }

      for (int y = 0; y < 2; y++) {
         for (int x = 0; x < 2; x++) {
            SubBlock usb = this.uSubBlocks[x][y];
            SubBlock vsb = this.vSubBlocks[x][y];
            usb.setPredict(ufill);
            vsb.setPredict(vfill);
         }
      }
   }

   private void doVPredict(MacroBlock aboveMb) {
      SubBlock[] aboveUSb = new SubBlock[2];
      SubBlock[] aboveVSb = new SubBlock[2];

      for (int x = 0; x < 2; x++) {
         aboveUSb[x] = aboveMb.getUSubBlock(x, 1);
         aboveVSb[x] = aboveMb.getVSubBlock(x, 1);
      }

      for (int y = 0; y < 2; y++) {
         for (int x = 0; x < 2; x++) {
            SubBlock usb = this.uSubBlocks[y][x];
            SubBlock vsb = this.vSubBlocks[y][x];
            int[][] ublock = new int[4][4];
            int[][] vblock = new int[4][4];

            for (int j = 0; j < 4; j++) {
               for (int i = 0; i < 4; i++) {
                  ublock[j][i] = aboveUSb[y].getMacroBlockPredict(1)[j][3];
                  vblock[j][i] = aboveVSb[y].getMacroBlockPredict(1)[j][3];
               }
            }

            usb.setPredict(ublock);
            vsb.setPredict(vblock);
         }
      }
   }

   private void doHPredict(MacroBlock leftMb) {
      SubBlock[] leftUSb = new SubBlock[2];
      SubBlock[] leftVSb = new SubBlock[2];

      for (int x = 0; x < 2; x++) {
         leftUSb[x] = leftMb.getUSubBlock(1, x);
         leftVSb[x] = leftMb.getVSubBlock(1, x);
      }

      for (int y = 0; y < 2; y++) {
         for (int x = 0; x < 2; x++) {
            SubBlock usb = this.uSubBlocks[x][y];
            SubBlock vsb = this.vSubBlocks[x][y];
            int[][] ublock = new int[4][4];
            int[][] vblock = new int[4][4];

            for (int j = 0; j < 4; j++) {
               for (int i = 0; i < 4; i++) {
                  ublock[i][j] = leftUSb[y].getMacroBlockPredict(2)[3][j];
                  vblock[i][j] = leftVSb[y].getMacroBlockPredict(2)[3][j];
               }
            }

            usb.setPredict(ublock);
            vsb.setPredict(vblock);
         }
      }
   }

   private void doTMPredict(MacroBlock aboveMb, MacroBlock leftMb, Frame frame) {
      MacroBlock ALMb = frame.getMacroBlock(this.x - 1, this.y - 1);
      SubBlock ALUSb = ALMb.getUSubBlock(1, 1);
      int alu = ALUSb.getDest()[3][3];
      SubBlock ALVSb = ALMb.getVSubBlock(1, 1);
      int alv = ALVSb.getDest()[3][3];
      SubBlock[] aboveUSb = new SubBlock[2];
      SubBlock[] leftUSb = new SubBlock[2];
      SubBlock[] aboveVSb = new SubBlock[2];
      SubBlock[] leftVSb = new SubBlock[2];

      for (int x = 0; x < 2; x++) {
         aboveUSb[x] = aboveMb.getUSubBlock(x, 1);
         leftUSb[x] = leftMb.getUSubBlock(1, x);
         aboveVSb[x] = aboveMb.getVSubBlock(x, 1);
         leftVSb[x] = leftMb.getVSubBlock(1, x);
      }

      for (int b = 0; b < 2; b++) {
         for (int a = 0; a < 4; a++) {
            for (int d = 0; d < 2; d++) {
               for (int c = 0; c < 4; c++) {
                  int upred = leftUSb[b].getDest()[3][a] + aboveUSb[d].getDest()[c][3] - alu;
                  upred = squeeze(upred);
                  this.uSubBlocks[d][b].setPixel(c, a, upred);
                  int vpred = leftVSb[b].getDest()[3][a] + aboveVSb[d].getDest()[c][3] - alv;
                  vpred = squeeze(vpred);
                  this.vSubBlocks[d][b].setPixel(c, a, vpred);
               }
            }
         }
      }
   }

   private void predictY(Frame frame) {
      switch (this.yMode) {
         case 0:
            this.handleDCPREDLookup(frame);
            break;
         case 1:
            this.handleVPREDLookup(frame);
            break;
         case 2:
            this.handleHPREDLookup(frame);
            break;
         case 3:
            this.handleTMPREDLookup(frame);
      }
   }

   private void handleTMPREDLookup(Frame frame) {
      MacroBlock aboveMb = frame.getMacroBlock(this.x, this.y - 1);
      MacroBlock leftMb = frame.getMacroBlock(this.x - 1, this.y);
      MacroBlock ALMb = frame.getMacroBlock(this.x - 1, this.y - 1);
      SubBlock ALSb = ALMb.getYSubBlock(3, 3);
      int al = ALSb.getDest()[3][3];
      SubBlock[] aboveYSb = new SubBlock[4];
      SubBlock[] leftYSb = new SubBlock[4];

      for (int x = 0; x < 4; x++) {
         aboveYSb[x] = aboveMb.getYSubBlock(x, 3);
      }

      for (int x = 0; x < 4; x++) {
         leftYSb[x] = leftMb.getYSubBlock(3, x);
      }

      for (int b = 0; b < 4; b++) {
         for (int a = 0; a < 4; a++) {
            for (int d = 0; d < 4; d++) {
               for (int c = 0; c < 4; c++) {
                  int pred = leftYSb[b].getDest()[3][a] + aboveYSb[d].getDest()[c][3] - al;
                  this.ySubBlocks[d][b].setPixel(c, a, squeeze(pred));
               }
            }
         }
      }
   }

   private void handleHPREDLookup(Frame frame) {
      MacroBlock leftMb = frame.getMacroBlock(this.x - 1, this.y);
      SubBlock[] leftYSb = new SubBlock[4];

      for (int x = 0; x < 4; x++) {
         leftYSb[x] = leftMb.getYSubBlock(3, x);
      }

      for (int y = 0; y < 4; y++) {
         for (int x = 0; x < 4; x++) {
            SubBlock sb = this.ySubBlocks[x][y];
            int[][] block = new int[4][4];

            for (int j = 0; j < 4; j++) {
               for (int i = 0; i < 4; i++) {
                  block[i][j] = leftYSb[y].getPredict(0, true)[3][j];
               }
            }

            sb.setPredict(block);
         }
      }

      SubBlock[] leftUSb = new SubBlock[2];

      for (int x = 0; x < 2; x++) {
         leftUSb[x] = leftMb.getYSubBlock(1, x);
      }
   }

   private void handleVPREDLookup(Frame frame) {
      MacroBlock aboveMb = frame.getMacroBlock(this.x, this.y - 1);
      SubBlock[] aboveYSb = new SubBlock[4];

      for (int x = 0; x < 4; x++) {
         aboveYSb[x] = aboveMb.getYSubBlock(x, 3);
      }

      for (int y = 0; y < 4; y++) {
         for (int x = 0; x < 4; x++) {
            SubBlock sb = this.ySubBlocks[x][y];
            int[][] block = new int[4][4];

            for (int j = 0; j < 4; j++) {
               for (int i = 0; i < 4; i++) {
                  block[i][j] = aboveYSb[x].getPredict(2, false)[i][3];
               }
            }

            sb.setPredict(block);
         }
      }
   }

   private void handleDCPREDLookup(Frame frame) {
      MacroBlock aboveMb = frame.getMacroBlock(this.x, this.y - 1);
      MacroBlock leftMb = frame.getMacroBlock(this.x - 1, this.y);
      boolean up_available = false;
      boolean left_available = false;
      int average = 0;
      if (this.x > 0) {
         left_available = true;
      }

      if (this.y > 0) {
         up_available = true;
      }

      int expected_dc;
      if (!up_available && !left_available) {
         expected_dc = 128;
      } else {
         if (up_available) {
            for (int j = 0; j < 4; j++) {
               SubBlock sb = aboveMb.getYSubBlock(j, 3);

               for (int i = 0; i < 4; i++) {
                  average += sb.getDest()[i][3];
               }
            }
         }

         if (left_available) {
            for (int j = 0; j < 4; j++) {
               SubBlock sb = leftMb.getYSubBlock(3, j);

               for (int i = 0; i < 4; i++) {
                  average += sb.getDest()[3][i];
               }
            }
         }

         int shift = 3;
         if (up_available) {
            shift++;
         }

         if (left_available) {
            shift++;
         }

         expected_dc = average + (1 << shift - 1) >> shift;
      }

      int[][] fill = new int[4][4];

      for (int y = 0; y < 4; y++) {
         for (int x = 0; x < 4; x++) {
            fill[x][y] = expected_dc;
         }
      }

      for (int y = 0; y < 4; y++) {
         for (int x = 0; x < 4; x++) {
            SubBlock sb = this.ySubBlocks[x][y];
            sb.setPredict(fill);
         }
      }
   }

   private void recon_mb() {
      for (int j = 0; j < 4; j++) {
         for (int i = 0; i < 4; i++) {
            SubBlock sb = this.ySubBlocks[i][j];
            sb.reconstruct();
         }
      }

      for (int var6 = 0; var6 < 2; var6++) {
         for (int i = 0; i < 2; i++) {
            SubBlock sb = this.uSubBlocks[i][var6];
            sb.reconstruct();
         }
      }

      for (int var7 = 0; var7 < 2; var7++) {
         for (int i = 0; i < 2; i++) {
            SubBlock sb = this.vSubBlocks[i][var7];
            sb.reconstruct();
         }
      }
   }

   void setFilterLevel(int value) {
      this.filterLevel = value;
   }

   void setSkipCoeff(int mbSkipCoeff) {
      this.skipCoeff = mbSkipCoeff;
   }

   void setUvMode(int mode) {
      this.uvMode = mode;
   }

   void setYMode(int yMode) {
      this.yMode = yMode;
   }

   private static int squeeze(int input) {
      return input > 255 ? 255 : Math.max(input, 0);
   }
}
