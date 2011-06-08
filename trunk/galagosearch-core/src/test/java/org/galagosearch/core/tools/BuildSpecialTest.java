// BSD License (http://www.galagosearch.org/license)
package org.galagosearch.core.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.HashMap;
import junit.framework.TestCase;
import org.galagosearch.core.index.DocumentIndicatorReader;
import org.galagosearch.core.index.DocumentPriorReader;
import org.galagosearch.tupleflow.Utility;

/**
 *
 * @author trevor
 */
public class BuildSpecialTest extends TestCase {

  public BuildSpecialTest(String testName) {
    super(testName);
  }

  public static String trecDocument(String docno, String text) {
    return "<DOC>\n<DOCNO>d" + docno + "</DOCNO>\n"
            + "<TEXT>\n" + text + "</TEXT>\n</DOC>\n";
  }

  public void testIndicators() throws Exception {
    File trecCorpusFile = null;
    File indicatorFile = null;
    File indexFile = null;
    File queryFile = null;

    try {
      // create a simple doc file, trec format:
      String trecCorpus = trecDocument("55", "This is a sample document")
              + trecDocument("59", "sample document two")
              + trecDocument("73", "sample document three")
              + trecDocument("10", "sample document four")
              + trecDocument("11", "sample document five");
      trecCorpusFile = Utility.createTemporary();
      Utility.copyStringToFile(trecCorpus, trecCorpusFile);

      String indicators = "d1\n"
              + "d5\n"
              + "d55\ttrue\n"
              + "d59\tfalse\n"
              + "d10\n";

      indicatorFile = Utility.createTemporary();
      Utility.copyStringToFile(indicators, indicatorFile);


      // now, try to build an index from that
      indexFile = Utility.createTemporary();
      indexFile.delete();
      App.main(new String[]{"build", indexFile.getAbsolutePath(),
                trecCorpusFile.getAbsolutePath()});

      App.main(new String[]{"build-special", indexFile.getAbsolutePath(),
                indicatorFile.getAbsolutePath(), "--type=indicator",
                "--partName=testingIndicators"});

      DocumentIndicatorReader reader = new DocumentIndicatorReader(indexFile.getAbsolutePath() + File.separator + "testingIndicators");

      String output = "0	true\n"
              + "2	true\n"
              + "3	false\n";

      DocumentIndicatorReader.KeyIterator iterator = reader.getIterator();
      StringBuilder sb = new StringBuilder();
      do {
        sb.append(iterator.getCurrentDocument()).append("\t").append(iterator.getCurrentIndicator()).append("\n");
      } while (iterator.nextKey());

      assert output.equals(sb.toString());

      // now test a query:
      String queries =
              "<parameters>\n"
              + "<query><number>1</number><text>sample</text></query>\n"
              + "<query><number>2</number><text>#filter( #indicator:part=testingIndicators() sample )</text></query>\n"
              + "</parameters>\n";
      queryFile = Utility.createTemporary();
      Utility.copyStringToFile(queries, queryFile);

      // test with batch search
      ByteArrayOutputStream byteArrayStream = new ByteArrayOutputStream();
      PrintStream printStream = new PrintStream(byteArrayStream);

      new App(printStream).run(new String[]{"batch-search",
                "--index=" + indexFile.getAbsolutePath(),
                queryFile.getAbsolutePath()});

      // Now, verify that some stuff exists
      String out = byteArrayStream.toString();

      String expected = "1 Q0 d10 1 -1.22350933 galago\n"
              + "1 Q0 d11 2 -1.22350933 galago\n"
              + "1 Q0 d59 3 -1.22350933 galago\n"
              + "1 Q0 d73 4 -1.22350933 galago\n"
              + "1 Q0 d55 5 -1.22483912 galago\n"
              + "2 Q0 d10 1 -1.22350933 galago\n"
              + "2 Q0 d55 2 -1.22483912 galago\n";

      assertEquals(expected, out);

    } finally {
      if (trecCorpusFile != null) {
        trecCorpusFile.delete();
      }
      if (indicatorFile != null) {
        indicatorFile.delete();
      }
      if (queryFile != null) {
        queryFile.delete();
      }
      if (indexFile != null) {
        Utility.deleteDirectory(indexFile);
      }
    }
  }

  public void testPriors() throws Exception {
    File trecCorpusFile = null;
    File priorFile = null;
    File indexFile = null;
    File queryFile = null;

    try {
      // create a simple doc file, trec format:
      String trecCorpus = trecDocument("55", "This is a sample document")
              + trecDocument("59", "sample document two")
              + trecDocument("73", "sample document three")
              + trecDocument("10", "sample document four")
              + trecDocument("11", "sample document five");
      trecCorpusFile = Utility.createTemporary();
      Utility.copyStringToFile(trecCorpus, trecCorpusFile);

      String priors = "d10\t-100.0\n"
              + "d11\t-90.0\n"
              + "d59\t-70.0\n"
              + "d73\t-60.0\n";

      priorFile = Utility.createTemporary();
      Utility.copyStringToFile(priors, priorFile);


      // now, try to build an index from that
      indexFile = Utility.createTemporary();
      indexFile.delete();
      App.main(new String[]{"build", indexFile.getAbsolutePath(),
                trecCorpusFile.getAbsolutePath()});

      App.main(new String[]{"build-special", indexFile.getAbsolutePath(),
                priorFile.getAbsolutePath(), "--type=prior",
                "--partName=testingPriors"});

      DocumentPriorReader reader = new DocumentPriorReader(indexFile.getAbsolutePath() + File.separator + "testingPriors");

      HashMap<Integer, Double> priorData = new HashMap();
      priorData.put(0, -100.0);
      priorData.put(1, -90.0);
      priorData.put(3, -70.0);
      priorData.put(4, -60.0);

      DocumentPriorReader.KeyIterator iterator = reader.getIterator();
      do {
        int doc = iterator.getCurrentDocument();
        double score = iterator.getCurrentScore();
        assert (priorData.get(doc) == score);
      } while (iterator.nextKey());

      // now test a query:
      String queries =
              "<parameters>\n"
              + "<query><number>1</number><text>sample</text></query>\n"
              + "<query><number>2</number><text>#combine( #prior:part=testingPriors() sample )</text></query>\n"
              + "</parameters>\n";
      queryFile = Utility.createTemporary();
      Utility.copyStringToFile(queries, queryFile);

      // test with batch search
      ByteArrayOutputStream byteArrayStream = new ByteArrayOutputStream();
      PrintStream printStream = new PrintStream(byteArrayStream);

      new App(printStream).run(new String[]{"batch-search",
                "--index=" + indexFile.getAbsolutePath(),
                queryFile.getAbsolutePath()});

      // Now, verify that some stuff exists
      String out = byteArrayStream.toString();

      String expected = "1 Q0 d10 1 -1.22350933 galago\n"
              + "1 Q0 d11 2 -1.22350933 galago\n"
              + "1 Q0 d59 3 -1.22350933 galago\n"
              + "1 Q0 d73 4 -1.22350933 galago\n"
              + "1 Q0 d55 5 -1.22483912 galago\n"
              + "2 Q0 d55 1 -0.61241956 galago\n"
              + "2 Q0 d73 2 -30.61175467 galago\n"
              + "2 Q0 d59 3 -35.61175467 galago\n"
              + "2 Q0 d11 4 -45.61175467 galago\n"
              + "2 Q0 d10 5 -50.61175467 galago\n";

      assertEquals(expected, out);

    } finally {
      if (trecCorpusFile != null) {
        trecCorpusFile.delete();
      }
      if (priorFile != null) {
        priorFile.delete();
      }
      if (queryFile != null) {
        queryFile.delete();
      }
      if (indexFile != null) {
        Utility.deleteDirectory(indexFile);
      }
    }
  }
}