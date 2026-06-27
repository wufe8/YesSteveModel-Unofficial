package rip.ysm.imagestream.utility;

import java.io.IOException;

public interface DataReader {
   int initialCacheSize = 8192;
   int getU8() throws IOException;
   int getU16() throws IOException;
   int getU24() throws IOException;
   int getU32() throws IOException;
   void read(byte[] var1) throws IOException;
   int getPosition();
   void skip(int var1);
   void moveTo(int var1);
   int getLength();
   void close() throws IOException;
}
