import importlib.util
from pathlib import Path
import unittest

def module(name,filename):
    s=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
    m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
corpus=module('test_curation','knowledge-adversarial-corpus.py')
crawler=module('test_public_crawler','fetch-juejin-scale.py')

class CorpusSelectionTest(unittest.TestCase):
    def test_existing_ids_hash_duplicates_and_nontechnical_sources_excluded(self):
        docs=[dict(slug=str(i),relative=str(i),body_sha256='hash'+str(i),sha256='file'+str(i),
            title='Redis缓存边界',chars=3000)for i in range(8)]
        docs[1]['body_sha256']=docs[0]['body_sha256']
        labels={d['slug']:dict(technical=True,sha256=d['sha256'])for d in docs}
        labels['3']['technical']=False
        tech,hard=corpus.choose(docs,labels,[docs[0]],technical_count=2,hard_count=1)
        self.assertEqual(len(tech+hard),3)
        self.assertTrue(all(d['relative']not in ['0','1','3']for d in tech+hard))
        self.assertEqual(len({d['body_sha256']for d in tech+hard}),3)

    def test_stale_curation_hash_does_not_authorize_changed_source(self):
        doc=dict(slug='a',relative='a',body_sha256='hash',sha256='changed',title='Java版本',chars=3000)
        with self.assertRaises(ValueError):
            corpus.choose([doc],{'a':dict(technical=True,sha256='original')},[],technical_count=1,hard_count=0)

    def test_public_post_discovery_deduplicates_real_post_ids(self):
        text='/post/1234567890123456789 /post/1234567890123456789 /post/9876543210987654321'
        self.assertEqual(crawler.article_ids(text),['1234567890123456789','9876543210987654321'])

if __name__=='__main__':unittest.main()
