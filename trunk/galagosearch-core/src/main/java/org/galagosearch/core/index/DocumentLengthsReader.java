// BSD License (http://www.galagosearch.org/license)
package org.galagosearch.core.index;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.galagosearch.core.retrieval.structured.NumberedDocumentDataIterator;
import org.galagosearch.core.types.NumberedDocumentData;

/**
 * Reads documents lengths from a document lengths file.
 * Iterator provides a useful interface for dumping the contents of the file.
 *
 * offset is the first document number (for sequential sharding purposes)
 * 
 * @author trevor, sjh
 */
public class DocumentLengthsReader {

  RandomAccessFile file;
  FileChannel channel;
  ByteBuffer buffer;
  int documentNumberOffset;
  int totalDocuments;

  public DocumentLengthsReader(String filename) throws FileNotFoundException, IOException {
    file = new RandomAccessFile(new File(filename), "r");

    file.seek(file.length() - 4);
    documentNumberOffset = file.readInt();
    file.seek(0);
    totalDocuments = (int) ((file.length() / 4) - 1); // final int is the offset

    // the last four bytes are the document offset;
    // thus they should not be readable from the channel map
    channel = file.getChannel();
    buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, (channel.size() - 4));
  }

  public void close() throws IOException {
    channel.close();
    file.close();
  }

  public int getLength(int document) {
    return buffer.getInt(document * 4);
  }

  public NumberedDocumentDataIterator getIterator() {
    return new Iterator();
  }

  /*
   * Iterator uses the buffer above
   * Perhaps it would be faster to use a new data stream...
   *
   */
  public class Iterator extends NumberedDocumentDataIterator {

    int current = 0;

    public void reset() throws IOException {
      current = 0;
    }

    public String getRecordString() {
      int docNum = current + documentNumberOffset;
      return docNum + ", " + getLength(current);
    }

    public boolean nextRecord() throws IOException {
      current++;
      if (current < totalDocuments) {
        return true;
      }
      return false;
    }

    public NumberedDocumentData getDocumentData() throws IOException {
      int docNum = current + documentNumberOffset;
      return new NumberedDocumentData("", "", docNum, getLength(current));
    }

    public String getKey() {
      return Integer.toString(current + documentNumberOffset);
    }
  }
}
