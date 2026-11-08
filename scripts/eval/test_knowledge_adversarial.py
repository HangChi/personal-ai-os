import importlib.util
from pathlib import Path
import unittest
import json
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch

def module(name,filename):
    s=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
    m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
evaluation=module('test_adversarial_eval','knowledge-adversarial-eval.py')

def hit(doc,chunk=0,content='quoted evidence'):
    return dict(documentId=doc,chunkIndex=chunk,content=content)

def case():
    return dict(id='q',category='technical',facts=[dict(id='f1'),dict(id='f2')],
        expected_document_ids=['a'],expected_action='answer')

def row(contexts=None):
    return dict(contexts=contexts or [],response=dict(answered=True),total_ms=1.)

class AdversarialMetricsTest(unittest.TestCase):
    def test_structured_judge_always_declares_json_mode_in_messages(self):
        requests=[]
        class Response:
            is_error=False
            def raise_for_status(self):pass
            def json(self):return dict(choices=[dict(message=dict(content=json.dumps(dict(ok=True))))])
        class Client:
            def __init__(self,**kwargs):pass
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def post(self,*args,**kwargs):requests.append(kwargs['json']);return Response()
        with TemporaryDirectory()as directory,patch.dict(sys.modules,{'httpx':SimpleNamespace(Client=Client)}):
            result=evaluation.structured(dict(base_url='http://unit.invalid',api_key='unit-only'),
                'unit-model','返回相关性标签',{'plain':'no format keyword'},Path(directory),'test')
        self.assertEqual(result,dict(ok=True))
        self.assertIn('JSON',requests[0]['messages'][0]['content'])
        self.assertEqual(requests[0]['response_format'],dict(type='json_object'))

    def test_unknown_labels_never_become_irrelevant(self):
        labels={('q','a:0'):dict(label='relevant',facts=[dict(id='f1')]),
                ('q','b:0'):dict(label='uncertain',facts=[]),
                ('q','c:0'):dict(label='irrelevant',facts=[])}
        result=evaluation.metrics(case(),row([hit('a'),hit('b'),hit('c')]),labels,{}, {})
        self.assertEqual(result['context_precision'],.5)
        self.assertEqual(result['context_label_coverage'],2/3)
        self.assertEqual(result['evidence_fact_recall'],.5)
        self.assertIsNone(result['answer_fact_precision'])

    def test_empty_context_is_not_perfect_precision(self):
        result=evaluation.metrics(case(),row(),{},dict(action='refuse',claims=[],covered_facts=[]),{})
        self.assertIsNone(result['context_precision'])
        self.assertEqual(result['evidence_fact_recall'],0)
        self.assertEqual(result['answer_fact_f1'],0)
        self.assertTrue(result['positive_refuse_or_clarify'])

    def test_cited_claim_must_use_the_cited_support(self):
        answer=dict(action='answer',claims=[dict(supported=True,relevant=True,supporting_context=2,citations=[1])],
            covered_facts=[dict(id='f1'),dict(id='f2')])
        result=evaluation.metrics(case(),row([hit('a'),hit('b')]),{},answer,{})
        self.assertEqual(result['answer_fact_precision'],1)
        self.assertEqual(result['answer_fact_recall'],1)
        self.assertEqual(result['citation_supported_claim_precision'],0)

    def test_literature_is_only_noise_relative_to_technical_query(self):
        q=case();r=row([hit('poem')])
        self.assertEqual(evaluation.metrics(q,r,{}, {}, {'poem':'unrelated'})['unrelated_context_fraction'],1)
        q['category']='literature'
        self.assertNotIn('unrelated_context_fraction',evaluation.metrics(q,r,{}, {}, {'poem':'unrelated'}))

    def test_duplicate_or_invented_pool_labels_fail_closed(self):
        items=[dict(key='a:0',content='actual quote')]
        with self.assertRaises(ValueError):
            evaluation.label_batch(dict(items=[]),items,{'f1'})
        with self.assertRaises(ValueError):
            evaluation.label_batch(dict(items=[dict(key='a:0',label='relevant',
                facts=[dict(id='f1',quote='invented')])]),items,{'f1'})

    def test_reference_anchors_are_checked_against_target_sources(self):
        q=dict(question='如何配置数据库连接池？',facts=[
            dict(id='f1',text='fact',anchors=[dict(source='a',quote='exists')]),
            dict(id='f2',text='fact 2',anchors=[dict(source='a',quote='missing')])])
        with self.assertRaises(ValueError):evaluation.validate_case(q,{'a':dict(text='exists')})

    def test_quote_validation_preserves_identifier_semantics(self):
        self.assertTrue(evaluation.exact('api_key uses altChunk',chr(96)+'api_key'+chr(96)+' uses **altChunk**'))
        self.assertFalse(evaluation.exact('apikey','api_key'))
        self.assertFalse(evaluation.exact('init','__init__'))
        self.assertFalse(evaluation.exact('API_key','api_key'))

if __name__=='__main__':unittest.main()
