import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('knowledge_scale_eval', Path(__file__).with_name('knowledge-scale-eval.py'))
evaluation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evaluation)


def hit(doc, chunk=0, content='evidence'):
    return dict(documentId=doc, chunkIndex=chunk, content=content)


class ScaleMetricsTest(unittest.TestCase):
    def test_document_rank_deduplicates_and_uses_fixed_precision_denominator(self):
        result = evaluation.rank_metrics([hit('x'), hit('a'), hit('a', 1), hit('b')], ['a', 'b'])
        self.assertEqual(result['rank'], 2)
        self.assertEqual(result['precision_at_5'], .4)
        self.assertEqual(result['recall_at_5'], 1)
        self.assertEqual(result['recall_at_1'], 0)
        self.assertTrue(result['all_sources_at_3'])

    def test_absent_gold_is_not_perfect_recall(self):
        self.assertIsNone(evaluation.rank_metrics([hit('x')], [])['recall_at_5'])
        self.assertEqual(evaluation.rank_metrics([], ['a'])['recall_at_5'], 0)

    def test_context_diversity_and_second_pass_preserve_order(self):
        rows = [hit('a'), hit('a', 1), hit('b'), hit('a', 2), hit('c')]
        self.assertEqual([(r['documentId'], r['chunkIndex']) for r in evaluation.select_context(rows)],
                         [('a', 0), ('b', 0), ('c', 0), ('a', 1)])
        self.assertEqual(len(evaluation.select_context(rows, 2)), 2)

    def test_citations_are_checked_against_exact_chunk_content(self):
        case = dict(required=[['result_unknown']], anchors=['evidence'])
        response = dict(answer='RESULT' + chr(92) + '_UNKNOWN [2]',
                        citations=[dict(index=1, **hit('a'))], answered=True)
        metrics = evaluation.response_metrics(case, response, [hit('a')],
                                             {('a', 0): evaluation.sha('evidence')})
        self.assertTrue(metrics['citation_chunks_valid'])
        self.assertFalse(metrics['citation_marks_valid'])
        self.assertEqual(metrics['keypoint_coverage'], 1)
        self.assertEqual(metrics['anchor_coverage'], 1)
        response['citations'][0]['content'] = 'wrong'
        self.assertFalse(evaluation.response_metrics(case, response, [], {})['citation_chunks_valid'])

    def test_paired_bootstrap_uses_only_shared_cases(self):
        result = evaluation.paired_delta({'a':0., 'b':.5}, {'a':1., 'c':0.})
        self.assertEqual(result, dict(n=1, delta=1., ci95=[1., 1.]))
        self.assertEqual(evaluation.paired_delta({}, {})['n'], 0)
        self.assertEqual(evaluation.quantile([0., 10.], .95), 9.5)

    def test_absent_citations_do_not_inflate_traceability(self):
        metrics = evaluation.response_metrics({},dict(answer='资料不足'),[],{})
        self.assertIsNone(metrics['citation_chunks_valid'])
        self.assertIsNone(metrics['citation_marks_valid'])
        self.assertFalse(metrics['has_citations'])

    def test_reranker_rejects_duplicate_indices(self):
        class Response:
            def raise_for_status(self): pass
            def json(self): return dict(results=[dict(index=0, relevance_score=.9)] * 2)
        class Client:
            def post(self, *args, **kwargs): return Response()
        with self.assertRaises(ValueError):
            evaluation.rerank(Client(), dict(RAG_RERANKER_MODEL='test', RAG_RERANKER_ENDPOINT='http://local'),
                              'query', [hit('a'), hit('b')])

    def test_dashscope_reranker_preserves_candidates_and_limits_input(self):
        calls = []
        class Response:
            def raise_for_status(self): pass
            def json(self):
                return dict(output=dict(results=[dict(index=0,relevance_score=.2),
                                                 dict(index=1,relevance_score=.9)]))
        class Client:
            def post(self,*args,**kwargs):
                calls.append(kwargs['json'])
                return Response()
        rows = [hit('a',content='a'*3000),hit('b')]
        ordered,_ = evaluation.rerank(Client(),dict(RAG_RERANKER_MODEL='test',
            RAG_RERANKER_ENDPOINT='http://local',RAG_RERANKER_PROTOCOL='dashscope'),'query',rows)
        self.assertEqual(ordered,[rows[1],rows[0]])
        self.assertIs(ordered[0],rows[1])
        self.assertEqual(len(calls[0]['input']['documents'][0]),2000)
        self.assertEqual(calls[0]['parameters']['top_n'],2)

    def test_reranker_rejects_nonfinite_scores(self):
        class Response:
            def raise_for_status(self): pass
            def json(self): return dict(results=[dict(index=0,relevance_score=float('nan'))])
        class Client:
            def post(self,*args,**kwargs): return Response()
        with self.assertRaises(ValueError):
            evaluation.rerank(Client(),dict(RAG_RERANKER_MODEL='test',
                RAG_RERANKER_ENDPOINT='http://local'),'query',[hit('a')])


if __name__ == '__main__':
    unittest.main()
