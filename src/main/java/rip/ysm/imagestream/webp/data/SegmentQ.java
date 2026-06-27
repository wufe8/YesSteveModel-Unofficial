package rip.ysm.imagestream.webp.data;

class SegmentQ {
   int strength;
   int index;
   int uvac;
   int uvdc;
   int y1ac;
   int y1dc;
   int y2ac;
   int y2dc;

   private static int squeeze(int val) {
      return val < 0 ? 0 : (val > 127 ? 127 : val);
   }

   void setDeltaQUVAC(int x) {
      this.uvac = LookUp.QAC[squeeze(this.index + x)];
   }

   void setDeltaQUVDC(int x) {
      this.uvdc = LookUp.QDC[squeeze(this.index + x)];
   }

   void setY1dc(int x) {
      this.y1dc = LookUp.QDC[squeeze(this.index + x)];
      this.y1ac = LookUp.QAC[squeeze(this.index)];
   }

   void setY2ac_delta_q(int x) {
      this.y2ac = LookUp.QAC[squeeze(this.index + x)] * 155 / 100;
      if (this.y2ac < 8) {
         this.y2ac = 8;
      }
   }

   void setY2DC(int y2dc_delta_q) {
      this.y2dc = LookUp.QDC[squeeze(this.index + y2dc_delta_q)] << 1;
   }
}
