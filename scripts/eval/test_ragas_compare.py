import hashlib
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('ragas_compare', Path(__file__).with_name('ragas-compare.py'))
evaluation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evaluation)


class EvaluationMetricsTest(unittest.TestCase):
    def case(self):
        return dict(expected_document_ids=['a', 'b'], required=[['720'], ['90']])

    def response(self):
        return dict(answer='720秒、90秒 [1]', answered=True,
            citations=[dict(index=1, documentId='a', chunkIndex=0, content='evidence')],
            retrieval=dict(status='READY', hits=[dict(documentId='x', content='noise'),
                dict(documentId='a', content='evidence'), dict(documentId='a', content='duplicate'),
                dict(documentId='b', content='more evidence')]))

    def test_document_rank_deduplicates_chunks_and_keeps_all_gold_documents(self):
        measured = evaluation.measure(self.case(), self.response(),
            {('a',0):hashlib.sha256(b'evidence').hexdigest()})
        self.assertEqual(measured['rank'], 2)
        self.assertEqual(measured['document_ids'], ['x','a','b'])
        self.assertEqual(measured['expected_document_coverage_at_5'], 1)
        self.assertTrue(measured['all_expected_documents_at_5'])
        self.assertTrue(measured['citation_chunks_valid'])
        self.assertTrue(measured['citation_marks_valid'])

    def test_citation_content_and_marks_are_independently_checked(self):
        response = self.response()
        response['answer'] = '720秒、90秒 [2]'
        measured = evaluation.measure(self.case(), response, {})
        self.assertFalse(measured['citation_chunks_valid'])
        self.assertFalse(measured['citation_marks_valid'])

    def test_infrastructure_degradation_is_not_a_correct_refusal(self):
        response = dict(answer='资料不足', answered=False, retrieval={'status':'DEGRADED'})
        self.assertFalse(evaluation.measure(self.case(), response, {})['safe_refusal'])
        response['retrieval']['status'] = 'INSUFFICIENT'
        self.assertTrue(evaluation.measure(self.case(), response, {})['safe_refusal'])

    def test_missing_hits_and_keypoints_do_not_pass(self):
        measured = evaluation.measure(self.case(), dict(answer='未决事项', answered=False), {})
        self.assertIsNone(measured['rank'])
        self.assertFalse(measured['all_expected_documents_at_5'])
        self.assertEqual(measured['keypoint_coverage'], 0)
        self.assertFalse(measured['all_keypoints'])

    def test_markdown_escaped_identifier_matches_the_rendered_keypoint(self):
        case = dict(expected_document_ids=[], required=[['RESULT_UNKNOWN']])
        response = dict(answer='RESULT' + chr(92) + '_UNKNOWN')
        self.assertTrue(evaluation.measure(case, response, {})['all_keypoints'])


if __name__ == '__main__':
    unittest.main()
