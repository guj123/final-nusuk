import com.example.nusukhelper.*;
import java.io.*; import java.nio.file.*; import java.util.*;
public class StoreTest {
  static int fails=0;
  static void eq(String name,Object got,Object want){ boolean ok=Objects.equals(got,want); if(!ok) fails++; System.out.println((ok?"ok   ":"FAIL ")+name+" -> "+got+(ok?"":" (wanted "+want+")")); }
  static File tmp(String s) throws Exception { File f=File.createTempFile("qatest",".json"); Files.write(f.toPath(),s.getBytes("UTF-8")); return f; }
  public static void main(String[] a) throws Exception {
    File f=File.createTempFile("qastore",".json"); f.delete();
    AnswerStore s=new AnswerStore(f);
    List<String> o=Arrays.asList("Sword of Allah","The Truthful, the Trustworthy (As-Sadiq al-Amin)","As-Siddiq (the Truthful)","Al-Faruq");
    String q="What title did the people of Makkah give the Prophet before the \"prophetic\" mission?";
    eq("unknown question",s.find(q,o),-1);
    s.record(q,o.get(1),false);
    eq("learned (unverified, used)",s.find(q,o),1);
    s.useUnverified=false; eq("unverified ignored when disabled",s.find(q,o),-1); s.useUnverified=true;
    s.save();
    AnswerStore s2=new AnswerStore(f); s2.load();
    eq("survives save/load",s2.find(q,o),1);
    eq("options reordered still found",s2.find(q,Arrays.asList(o.get(3),o.get(1),o.get(0),o.get(2))),1);
    // the user's scenario: wrong first, then the right one is chosen later
    String q2="Which surah discusses the Battle of Badr in detail?";
    List<String> o2=Arrays.asList("Al Imran","Muhammad","Al-Anfal","At-Tawba");
    s2.markWrong(q2,"Al Imran"); eq("one wrong -> still unknown",s2.find(q2,o2),-1);
    s2.markRight(q2,"Al-Anfal"); eq("right chosen later -> used",s2.find(q2,o2),2);
    s2.markWrong(q2,"Al-Anfal"); eq("verified right is never overwritten by a wrong mark",s2.find(q2,o2),2);
    // a right answer that was wrongly marked wrong is cleared again
    s2.markWrong("q three alpha","B"); s2.record("q three alpha","B",true);
    eq("right answer removed from wrong list",s2.find("q three alpha",Arrays.asList("A","B","C","D")),1);
    s2.save();
    String json=new String(Files.readAllBytes(f.toPath()),"UTF-8"); System.out.println(json);
    AnswerStore s3=new AnswerStore(f); s3.load(); eq("reload",s3.find(q2,o2),2);
    // structured import (right/unverified/wrong); imported "right" is only unverified
    File imp=tmp("{\"right\":{\"How many verses are in surat al fatiha\":\"7 verses\"},\"wrong\":{\"which two cities does the dajjal not enter\":[\"Cairo\",\"Baghdad\"]}}");
    eq("structured import count",s3.importFile(imp),1);
    eq("imported answer used",s3.find("how many verses are in Surat Al-Fatiha?",Arrays.asList("5 verses","6 verses","7 verses","8 verses")),2);
    eq("imported wrong list eliminates",s3.find("which two cities does the dajjal not enter",Arrays.asList("Cairo","Baghdad","Makkah and Madinah","Jerusalem")),-1);
    // imported wrong must not remove a verified answer
    File bad=tmp("{\"wrong\":{\"which surah discusses the battle of badr in detail\":[\"Al-Anfal\"]}}");
    s3.importFile(bad); eq("verified survives hostile import",s3.find(q2,o2),2);
    // plain map import + old format import
    eq("plain map import",s3.importFile(tmp("{\"Who built the Kaaba with Ibrahim?\":\"Ismail\",\"x\":5}")),1);
    eq("plain imported used",s3.find("who built the kaaba with ibrahim",Arrays.asList("Nuh","Ismail","Musa","Isa")),1);
    eq("old format import",s3.importFile(tmp("{\"answers\":[{\"q\":\"Old Q?\",\"a\":\"Old A\",\"verified\":true}],\"wrong\":[]}")),1);
    eq("old format used",s3.find("old q",Arrays.asList("x","Old A","y","z")),1);
    // result detector
    eq("right",ResultDetector.classify("Congratulations! Your key opened the door"),1);
    eq("wrong beats 'correct answer is'",ResultDetector.classify("Wrong answer. The correct answer is Al-Anfal"),-1);
    eq("unknown",ResultDetector.classify("Check your answer"),0);
    System.out.println("\n"+(fails==0?"ALL PASSED":fails+" FAILED"));
  }}
