import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.nodes.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;
import java.nio.file.*;
public class CheckYaml {
 public static void main(String[] args) throws Exception {
  System.out.println("COMPILED: "+com.theveloper.pixelplay.data.network.lyrics.LyricsfileParser.INSTANCE.parse(Files.readString(Path.of(args[0]))));
  var options=new LoaderOptions();options.setCodePointLimit(512000);options.setMaxAliasesForCollections(0);options.setNestingDepthLimit(20);options.setAllowDuplicateKeys(false);
  var dumper=new DumperOptions();
  var resolver=new Resolver(){public Tag resolve(NodeId kind,String value,boolean implicit){return kind==NodeId.scalar?Tag.STR:super.resolve(kind,value,implicit);}};
  var yaml=new Yaml(new SafeConstructor(options),new Representer(dumper),dumper,options,resolver);
  var doc=(java.util.Map<?,?>)yaml.load(Files.readString(Path.of(args[0])));
  System.out.println(doc.keySet());System.out.println(doc.get("version").getClass());
  var line=(java.util.Map<?,?>)((java.util.List<?>)doc.get("lines")).get(0);System.out.println(line.keySet());System.out.println(line.get("start_ms"));
 }
}
