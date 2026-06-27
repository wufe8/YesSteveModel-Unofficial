package rip.ysm.imagestream.webp.data;

class SubBlock {
   int[][] dest;
   int[][] diff;
   int mode;
   private final SubBlock top;
   private final SubBlock left;
   private boolean hasNoZeroToken;
   final MacroBlock macroBlock;
   private final Layer layer;
   private int[][] predict;
   private int[] tokens;

   static int layerToType(Layer layer, Boolean hasY2) {
      return switch (layer) {
         case U, V -> 2;
         case Y1 -> hasY2 ? 0 : 3;
         case Y2 -> 1;
      };
   }

   SubBlock(MacroBlock macroBlock, SubBlock top, SubBlock left, Layer layer) {
      this.macroBlock = macroBlock;
      this.layer = layer;
      this.top = top;
      this.left = left;
      this.mode = 0;
      this.tokens = new int[16];
   }

   private static int getExtraDCT(BitDecoder bc2, int[] p) {
      int v = 0;
      int offset = 0;

      do {
         v += v + bc2.getProbBit(p[offset]);
      } while (p[++offset] > 0);

      return v;
   }

   void decode(BitDecoder bc2, int[][][][] coef_probs, int ilc, int type, boolean hasY2) {
      int startAt = 0;
      if (hasY2) {
         startAt = 1;
      }

      int lc = ilc;
      int c = 0;
      int v = 1;

      for (boolean skip = false; v != 11 && c + startAt < 16; c++) {
         if (!skip) {
            v = bc2.getTree(LookUp.EOB_COEF_TREE, coef_probs[type][LookUp.CO_BANDS[c + startAt]][lc]);
         } else {
            v = bc2.skipTree(coef_probs[type][LookUp.CO_BANDS[c + startAt]][lc]);
         }

         int dv = decodeToken(bc2, v);
         lc = 0;
         skip = false;
         if (dv == 1 || dv == -1) {
            lc = 1;
         } else if (dv <= 1 && dv >= -1) {
            skip = true;
         } else {
            lc = 2;
         }

         if (v != 11) {
            this.tokens[LookUp.ZIGZAGS[c + startAt]] = dv;
         }
      }

      this.hasNoZeroToken = false;

      for (int x = 0; x < 16; x++) {
         if (this.tokens[x] != 0) {
            this.hasNoZeroToken = true;
            break;
         }
      }
   }

   private static int decodeToken(BitDecoder bc2, int v) {
      int r = switch (v) {
         case 5 -> 5 + getExtraDCT(bc2, LookUp.PC1);
         case 6 -> 7 + getExtraDCT(bc2, LookUp.PC2);
         case 7 -> 11 + getExtraDCT(bc2, LookUp.PC3);
         case 8 -> 19 + getExtraDCT(bc2, LookUp.PC4);
         case 9 -> 35 + getExtraDCT(bc2, LookUp.PC5);
         case 10 -> 67 + getExtraDCT(bc2, LookUp.PC6);
         default -> v;
      };
      if (v != 0 && v != 11 && bc2.getBit() > 0) {
         r = -r;
      }

      return r;
   }

   void dequantSubBlock(Frame frame, Integer Dc) {
      int[] adjustedValues = new int[16];

      for (int i = 0; i < 16; i++) {
         int dq;
         if (this.layer != Layer.U && this.layer != Layer.V) {
            dq = frame.getSegmentQuants().segQuants[this.macroBlock.key].y1ac;
            if (i == 0) {
               dq = frame.getSegmentQuants().segQuants[this.macroBlock.key].y1dc;
            }
         } else {
            dq = frame.getSegmentQuants().segQuants[this.macroBlock.key].uvac;
            if (i == 0) {
               dq = frame.getSegmentQuants().segQuants[this.macroBlock.key].uvdc;
            }
         }

         adjustedValues[i] = this.tokens[i] * dq;
      }

      if (Dc != null) {
         adjustedValues[0] = Dc;
      }

      this.diff = Transform.cosine(adjustedValues, this.macroBlock.cache16);
   }

   SubBlock getAbove() {
      return this.top;
   }

   int[][] getDest() {
      return this.dest != null ? this.dest : new int[4][4];
   }

   int[][] getDiff() {
      return this.diff;
   }

   SubBlock getLeft() {
      return this.left;
   }

   int[][] getMacroBlockPredict(int intra_mode) {
      if (this.dest != null) {
         return this.dest;
      } else {
         int rv = 127;
         if (intra_mode == 2) {
            rv = 129;
         }

         int[][] r = new int[4][4];

         for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
               r[i][j] = rv;
            }
         }

         return r;
      }
   }

   Layer getLayer() {
      return this.layer;
   }

   int[][] getPredict(int bMode, boolean left) {
      if (this.dest != null) {
         return this.dest;
      } else if (this.predict != null) {
         return this.predict;
      } else {
         int rv = 127;
         if ((bMode == 1 || bMode == 0 || bMode == 2 || bMode == 3 || bMode == 6 || bMode == 5 || bMode == 8) && left) {
            rv = 129;
         }

         int[][] r = new int[4][4];

         for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
               r[i][j] = rv;
            }
         }

         return r;
      }
   }

   int[] getTokens() {
      return this.tokens;
   }

   boolean hasNoZeroToken() {
      return this.hasNoZeroToken;
   }

   private boolean isDest() {
      return this.dest != null;
   }

   void predict(Frame frame) {
      SubBlock aboveSb = frame.getTopSubBlock(this, this.layer);
      SubBlock leftSb = frame.getLeftSubBlock(this, this.layer);
      int[] top = new int[4];
      int[] left = new int[4];
      top[0] = aboveSb.getPredict(this.mode, false)[0][3];
      top[1] = aboveSb.getPredict(this.mode, false)[1][3];
      top[2] = aboveSb.getPredict(this.mode, false)[2][3];
      top[3] = aboveSb.getPredict(this.mode, false)[3][3];
      left[0] = leftSb.getPredict(this.mode, true)[3][0];
      left[1] = leftSb.getPredict(this.mode, true)[3][1];
      left[2] = leftSb.getPredict(this.mode, true)[3][2];
      left[3] = leftSb.getPredict(this.mode, true)[3][3];
      SubBlock AL = frame.getLeftSubBlock(aboveSb, this.layer);
      int al;
      if (!leftSb.isDest() && !aboveSb.isDest()) {
         al = AL.getPredict(this.mode, false)[3][3];
      } else if (!aboveSb.isDest()) {
         al = AL.getPredict(this.mode, false)[3][3];
      } else {
         al = AL.getPredict(this.mode, true)[3][3];
      }

      SubBlock AR = frame.getTopRightSubBlock(this, this.layer);
      int[] ar = new int[]{
         AR.getPredict(this.mode, false)[0][3],
         AR.getPredict(this.mode, false)[1][3],
         AR.getPredict(this.mode, false)[2][3],
         AR.getPredict(this.mode, false)[3][3]
      };
      switch (this.mode) {
         case 0:
            this.setB_DC_PRED(top, left);
            break;
         case 1:
            this.setB_TM_PRED(top, al, left);
            break;
         case 2:
            this.setB_VE_PRED(al, top, ar);
            break;
         case 3:
            this.setB_HE_PRED(al, left);
            break;
         case 4:
            this.setB_LD_PRED(top, ar);
            break;
         case 5:
            this.setB_RD_PRED(top, left, al);
            break;
         case 6:
            this.setB_VR_PRED(top, left, al);
            break;
         case 7:
            this.setB_VL_PRED(top, ar);
            break;
         case 8:
            this.setB_HD_PRED(top, left, al);
            break;
         case 9:
            this.setB_HU_PRED(left);
      }
   }

   private void setB_HE_PRED(int al, int[] left1) {
      int[][] p = new int[4][4];
      int[] lp = new int[]{
         al + 2 * left1[0] + left1[1] + 2 >> 2,
         left1[0] + 2 * left1[1] + left1[2] + 2 >> 2,
         left1[1] + 2 * left1[2] + left1[3] + 2 >> 2,
         left1[2] + 2 * left1[3] + left1[3] + 2 >> 2
      };

      for (int r = 0; r < 4; r++) {
         for (int c = 0; c < 4; c++) {
            p[c][r] = lp[r];
         }
      }

      this.predict = p;
   }

   private void setB_VE_PRED(int al, int[] top1, int[] ar) {
      int[][] p = new int[4][4];
      int[] ap = new int[]{
         al + 2 * top1[0] + top1[1] + 2 >> 2,
         top1[0] + 2 * top1[1] + top1[2] + 2 >> 2,
         top1[1] + 2 * top1[2] + top1[3] + 2 >> 2,
         top1[2] + 2 * top1[3] + ar[0] + 2 >> 2
      };

      for (int r = 0; r < 4; r++) {
         for (int c = 0; c < 4; c++) {
            p[c][r] = ap[c];
         }
      }

      this.predict = p;
   }

   private void setB_TM_PRED(int[] top1, int al, int[] left1) {
      int[][] p = new int[4][4];

      for (int r = 0; r < 4; r++) {
         for (int c = 0; c < 4; c++) {
            int pred = top1[c] - al + left1[r];
            if (pred < 0) {
               pred = 0;
            }

            if (pred > 255) {
               pred = 255;
            }

            p[c][r] = pred;
         }
      }

      this.predict = p;
   }

   private void setB_DC_PRED(int[] top1, int[] left1) {
      int[][] p = new int[4][4];
      int expected_dc = 0;

      for (int i = 0; i < 4; i++) {
         expected_dc += top1[i];
         expected_dc += left1[i];
      }

      expected_dc = expected_dc + 4 >> 3;

      for (int y = 0; y < 4; y++) {
         for (int x = 0; x < 4; x++) {
            p[x][y] = expected_dc;
         }
      }

      this.predict = p;
   }

   private void setB_LD_PRED(int[] top, int[] ar) {
      int[][] p = new int[4][4];
      p[0][0] = top[0] + top[1] * 2 + top[2] + 2 >> 2;
      p[1][0] = p[0][1] = top[1] + top[2] * 2 + top[3] + 2 >> 2;
      p[2][0] = p[1][1] = p[0][2] = top[2] + top[3] * 2 + ar[0] + 2 >> 2;
      p[3][0] = p[2][1] = p[1][2] = p[0][3] = top[3] + ar[0] * 2 + ar[1] + 2 >> 2;
      p[3][1] = p[2][2] = p[1][3] = ar[0] + ar[1] * 2 + ar[2] + 2 >> 2;
      p[3][2] = p[2][3] = ar[1] + ar[2] * 2 + ar[3] + 2 >> 2;
      p[3][3] = ar[2] + ar[3] * 2 + ar[3] + 2 >> 2;
      this.predict = p;
   }

   private void setB_RD_PRED(int[] top, int[] left, int al) {
      int[] pp = new int[9];
      int[][] p = new int[4][4];
      pp[0] = left[3];
      pp[1] = left[2];
      pp[2] = left[1];
      pp[3] = left[0];
      pp[4] = al;
      pp[5] = top[0];
      pp[6] = top[1];
      pp[7] = top[2];
      pp[8] = top[3];
      p[0][3] = pp[0] + pp[1] * 2 + pp[2] + 2 >> 2;
      p[1][3] = p[0][2] = pp[1] + pp[2] * 2 + pp[3] + 2 >> 2;
      p[2][3] = p[1][2] = p[0][1] = pp[2] + pp[3] * 2 + pp[4] + 2 >> 2;
      p[3][3] = p[2][2] = p[1][1] = p[0][0] = pp[3] + pp[4] * 2 + pp[5] + 2 >> 2;
      p[3][2] = p[2][1] = p[1][0] = pp[4] + pp[5] * 2 + pp[6] + 2 >> 2;
      p[3][1] = p[2][0] = pp[5] + pp[6] * 2 + pp[7] + 2 >> 2;
      p[3][0] = pp[6] + pp[7] * 2 + pp[8] + 2 >> 2;
      this.predict = p;
   }

   private void setB_VR_PRED(int[] top, int[] left, int al) {
      int[][] p = new int[4][4];
      int pp1 = left[2];
      int pp2 = left[1];
      int pp3 = left[0];
      int pp5 = top[0];
      int pp6 = top[1];
      int pp7 = top[2];
      int pp8 = top[3];
      p[0][3] = pp1 + pp2 * 2 + pp3 + 2 >> 2;
      p[0][2] = pp2 + pp3 * 2 + al + 2 >> 2;
      p[1][3] = p[0][1] = pp3 + al * 2 + pp5 + 2 >> 2;
      p[1][2] = p[0][0] = al + pp5 + 1 >> 1;
      p[2][3] = p[1][1] = al + pp5 * 2 + pp6 + 2 >> 2;
      p[2][2] = p[1][0] = pp5 + pp6 + 1 >> 1;
      p[3][3] = p[2][1] = pp5 + pp6 * 2 + pp7 + 2 >> 2;
      p[3][2] = p[2][0] = pp6 + pp7 + 1 >> 1;
      p[3][1] = pp6 + pp7 * 2 + pp8 + 2 >> 2;
      p[3][0] = pp7 + pp8 + 1 >> 1;
      this.predict = p;
   }

   private void setB_VL_PRED(int[] top, int[] ar) {
      int[][] p = new int[4][4];
      p[0][0] = top[0] + top[1] + 1 >> 1;
      p[0][1] = top[0] + top[1] * 2 + top[2] + 2 >> 2;
      p[0][2] = p[1][0] = top[1] + top[2] + 1 >> 1;
      p[1][1] = p[0][3] = top[1] + top[2] * 2 + top[3] + 2 >> 2;
      p[1][2] = p[2][0] = top[2] + top[3] + 1 >> 1;
      p[1][3] = p[2][1] = top[2] + top[3] * 2 + ar[0] + 2 >> 2;
      p[3][0] = p[2][2] = top[3] + ar[0] + 1 >> 1;
      p[3][1] = p[2][3] = top[3] + ar[0] * 2 + ar[1] + 2 >> 2;
      p[3][2] = ar[0] + ar[1] * 2 + ar[2] + 2 >> 2;
      p[3][3] = ar[1] + ar[2] * 2 + ar[3] + 2 >> 2;
      this.predict = p;
   }

   private void setB_HD_PRED(int[] top, int[] left, int al) {
      int[][] p = new int[4][4];
      int[] pp = new int[]{left[3], left[2], left[1], left[0], al, top[0], top[1], top[2], top[3]};
      p[0][3] = pp[0] + pp[1] + 1 >> 1;
      p[1][3] = pp[0] + pp[1] * 2 + pp[2] + 2 >> 2;
      p[0][2] = p[2][3] = pp[1] + pp[2] + 1 >> 1;
      p[1][2] = p[3][3] = pp[1] + pp[2] * 2 + pp[3] + 2 >> 2;
      p[2][2] = p[0][1] = pp[2] + pp[3] + 1 >> 1;
      p[3][2] = p[1][1] = pp[2] + pp[3] * 2 + pp[4] + 2 >> 2;
      p[2][1] = p[0][0] = pp[3] + pp[4] + 1 >> 1;
      p[3][1] = p[1][0] = pp[3] + pp[4] * 2 + pp[5] + 2 >> 2;
      p[2][0] = pp[4] + pp[5] * 2 + pp[6] + 2 >> 2;
      p[3][0] = pp[5] + pp[6] * 2 + pp[7] + 2 >> 2;
      this.predict = p;
   }

   private void setB_HU_PRED(int[] left) {
      int[][] p = new int[4][4];
      p[0][0] = left[0] + left[1] + 1 >> 1;
      p[1][0] = left[0] + left[1] * 2 + left[2] + 2 >> 2;
      p[2][0] = p[0][1] = left[1] + left[2] + 1 >> 1;
      p[3][0] = p[1][1] = left[1] + left[2] * 2 + left[3] + 2 >> 2;
      p[2][1] = p[0][2] = left[2] + left[3] + 1 >> 1;
      p[3][1] = p[1][2] = left[2] + left[3] * 2 + left[3] + 2 >> 2;
      p[2][2] = p[3][2] = p[0][3] = p[1][3] = p[2][3] = p[3][3] = left[3];
      this.predict = p;
   }

   void reconstruct() {
      int[][] p = this.getPredict(1, false);
      int[][] dd = new int[4][4];

      for (int r = 0; r < 4; r++) {
         for (int c = 0; c < 4; c++) {
            int a = this.diff[r][c] + p[r][c];
            dd[r][c] = a < 0 ? 0 : (a > 255 ? 255 : a);
         }
      }

      this.dest = dd;
      this.diff = null;
      this.predict = null;
      this.tokens = null;
   }

   void setMode(int mode) {
      this.mode = mode;
   }

   void setPixel(int x, int y, int p) {
      if (this.dest == null) {
         this.dest = new int[4][4];
      }

      this.dest[x][y] = p;
   }

   void setPredict(int[][] predict) {
      this.predict = predict;
   }

   static enum Layer {
      U,
      V,
      Y1,
      Y2;
   }
}
