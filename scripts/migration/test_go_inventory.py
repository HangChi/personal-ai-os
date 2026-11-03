"""验证最容易导致静默漏项的 Java 索引边界。"""

import unittest

from go_inventory import annotations, declarations, mapping_paths, signature_after


class JavaIndexTest(unittest.TestCase):
    def test_comments_and_literals_do_not_create_annotations(self):
        source = '''// @GetMapping("/wrong")
/* @Tool(name="wrong") */
class Example {
    String s = "@PostMapping(\\"/wrong\\")";
    @GetMapping(value = "/actual", produces = "text/event-stream")
    public String actual(@RequestParam(defaultValue = "(x)") String query) { return query; }
}'''
        found = annotations(source)
        self.assertEqual(["GetMapping", "RequestParam"], [item["name"] for item in found])
        self.assertEqual(["/actual"], mapping_paths(found[0]))
        self.assertEqual("actual", signature_after(source, found[0]["end"])["name"])

    def test_bare_named_and_multiple_mapping_paths(self):
        for source, expected in [('@GetMapping', [""]),
                                 ('@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)', [""]),
                                 ('@GetMapping("/{id}")', ["/{id}"]),
                                 ('@RequestMapping(path = {"/a", "/b"})', ["/a", "/b"]),
                                 ('@RequestMapping({"/a", "/b"})', ["/a", "/b"]),
                                 ('@RequestMapping("${server.error.path:/error}")', ["${server.error.path:/error}"])]:
            with self.subTest(source=source):
                self.assertEqual(expected, mapping_paths(annotations(source)[0]))

    def test_compact_controller_and_nested_records(self):
        source = '''@RestController @RequestMapping("/api/v1/example")
public class Example {
 @GetMapping public ApiResponse<List<Item>> list(){return null;}
 public record Item(String id, List<String> values) {}
}'''
        found = annotations(source)
        self.assertEqual("list", signature_after(source, found[-1]["end"])["name"])
        self.assertEqual(["Example", "Item"], [item["name"] for item in declarations(source)])
        self.assertIn("List<String> values", declarations(source)[1]["signature"])

    def test_text_blocks_are_not_annotations(self):
        source = 'class Example { String sql = """\n@GetMapping("/wrong")\n"""; @Scheduled(fixedDelay=1000) void tick() {} }'
        found = annotations(source)
        self.assertEqual(["Scheduled"], [item["name"] for item in found])
        self.assertEqual("tick", signature_after(source, found[0]["end"])["name"])


if __name__ == "__main__":
    unittest.main()
