package rip.ysm.imagestream.webp.data;

class ColorMap {
   private final int[] colors;
   private final int shift;
   private static final long HASHMULTY = 506832829L;

   private static int hashPix(int argb, int shift) {
      return (int)((argb * 506832829L & 4294967295L) >> shift);
   }

   ColorMap(int hashBits) {
      int hashSize = 1 << hashBits;
      this.colors = new int[hashSize];
      this.shift = 32 - hashBits;
   }

   int lookup(int key) {
      return this.colors[key];
   }

   void set(int key, int argb) {
      this.colors[key] = argb;
   }

   void insert(int argb) {
      this.colors[this.index(argb)] = argb;
   }

   int index(int argb) {
      return hashPix(argb, this.shift);
   }

   int contains(int argb) {
      int key = this.index(argb);
      return this.colors[key] == argb ? key : -1;
   }
}
