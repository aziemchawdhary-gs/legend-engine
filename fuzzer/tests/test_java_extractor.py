from fuzzer.miner.java_extractor import extract_pure_strings_from_java, extract_from_java_directory
import tempfile
import os


def test_extract_simple_string():
    java_code = '''
    @Test
    public void testMapping() {
        test("###Mapping\\n" +
             "Mapping test::mapping\\n" +
             "(\\n" +
             ")\\n");
    }
    '''
    strings = extract_pure_strings_from_java(java_code)
    assert len(strings) >= 1
    assert "###Mapping" in strings[0]
    assert "Mapping test::mapping" in strings[0]


def test_extract_multiline_concat():
    java_code = '''
    String code = "###Pure\\n" +
                  "Class test::Person\\n" +
                  "{\\n" +
                  "  name: String[1];\\n" +
                  "}\\n";
    '''
    strings = extract_pure_strings_from_java(java_code)
    assert len(strings) >= 1
    assert "Class test::Person" in strings[0]


def test_extract_from_directory():
    with tempfile.TemporaryDirectory() as tmpdir:
        with open(os.path.join(tmpdir, "TestGrammar.java"), "w") as f:
            f.write('''
            public class TestGrammar {
                @Test
                public void test1() {
                    test("###Pure\\nClass a::B {}\\n");
                }
            }
            ''')
        strings = extract_from_java_directory(tmpdir)
        assert len(strings) >= 1
        assert "###Pure" in strings[0]


def test_ignores_non_pure_strings():
    java_code = '''
    String x = "hello world";
    String y = "no section here";
    test("###Mapping\\nMapping a::b ()\\n");
    '''
    strings = extract_pure_strings_from_java(java_code)
    assert len(strings) == 1
    assert "###Mapping" in strings[0]
