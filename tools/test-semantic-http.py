"""Acceptance of a running demo server. Creates isolated conversations; never confirms dispatch."""
import argparse
import json
import time
import urllib.request
from pathlib import Path


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--base-url', default='http://127.0.0.1:8080/api/')
    args.add_argument('--output', default='backend/target/semantic-http-acceptance.json')
    args.add_argument('--resume-conversation')
    options = args.parse_args()
    evidence = {'checks': [], 'turns': [], 'conversations': [], 'plans': [], 'confirmedDispatches': 0}
    created_plans = set()

    def request(path, body=None, method=None):
        req = urllib.request.Request(options.base_url.rstrip('/') + '/' + path,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8'), method=method,
            headers={'X-User-Id': 'user1', 'Content-Type': 'application/json; charset=utf-8'})
        with urllib.request.urlopen(req, timeout=190) as response:
            text = response.read().decode('utf-8')
            if 'text/event-stream' in response.headers.get('Content-Type', ''):
                events = []
                for block in text.replace('\r\n', '\n').split('\n\n'):
                    values = {}
                    for line in block.splitlines():
                        if ':' in line:
                            key, value = line.split(':', 1)
                            values[key] = value.strip()
                    if 'event' in values and 'data' in values:
                        events.append({'type': values['event'], 'data': json.loads(values['data'])})
                return events
            value = json.loads(text)
            if value.get('code', 0) != 0:
                raise RuntimeError(value.get('message', 'API failed'))
            return value.get('data', value)

    def check(name, result):
        evidence['checks'].append({'name': name, 'passed': bool(result)})

    def turn(conversation, text):
        start = time.monotonic()
        events = request('agent/chat', {'conversationId': conversation, 'message': text})
        for event in events:
            if event['type'] == 'conversation': conversation = event['data']['conversationId']
            if event['type'] == 'plan': created_plans.add(event['data']['planId'])
        evidence['turns'].append({'conversationId': conversation, 'message': text,
                                  'latencyMs': round((time.monotonic() - start) * 1000), 'events': events})
        check('SSE completes: ' + text, any(e['type'] == 'done' for e in events) and not any(e['type'] == 'error' for e in events))
        return conversation, events

    def payload(events, kind):
        return next((e['data'] for e in events if e['type'] == kind), None)

    def text(events):
        return ''.join(e['data'].get('delta', '') for e in events if e['type'] == 'text')

    def a_preview(events):
        p = payload(events, 'preview')
        return p is not None and p['total'] == 3 and all(r['companyCode'] == 'A' for r in p['records'])

    try:
        for index, first in enumerate(['先看看 A 公司的销售台账', '查一下 B 公司销售报表有哪些可以派单']):
            conv, events = turn(None, first)
            evidence['conversations'].append(conv)
            request('agent/conversations/' + conv + '/title', {'title': '语义修复验收-' + ('省略公司' if index == 0 else '拒绝后纠正')}, 'PUT')
            if index == 0:
                check('Initial A query returns only A sales', a_preview(events))
                conv, events = turn(conv, '那 B 公司呢？')
            check('B request is refused without another company preview', '无权查看 B 公司' in text(events) and payload(events, 'preview') is None)
            conv, events = turn(conv, '现在我只想派销售报表的')
            check('Omitted company retains rejected B', '无权查看 B 公司' in text(events) and payload(events, 'plan') is None)
            conv, events = turn(conv, 'A公司销售报表的')
            check('Explicit A correction succeeds', a_preview(events))
            if index == 0:
                source = payload(events, 'preview')['previewId']
                conv, events = turn(conv, '排除SO2026002')
                selection = request('agent/conversations/' + conv + '/selection')
                check('Exclusion uses original preview and exact record key', selection['previewId'] == source
                      and selection['excludedRecords'] == [{'reportId': 'rpt-sales-order', 'recordId': '2'}]
                      and payload(events, 'preview') is None)
                conv, events = turn(conv, '给剩下的生成待确认派单清单')
                plan = payload(events, 'plan')
                check('Plan stays pending and excludes SO2026002', plan is not None and plan['status'] == 'PENDING'
                      and plan['count'] == 2 and {r['docNo'] for r in plan['records']} == {'SO2026001', 'SO2026007'})
                if plan:
                    evidence['plans'].append(plan)
                    conv, events = turn(conv, '取消这份待确认清单')
                    check('Cancel command cancels only the created plan', request('dispatch/plans/' + plan['planId'])['status'] == 'CANCELLED')
        conv, events = turn(None, '查询A公司销售报表')
        evidence['conversations'].append(conv)
        request('agent/conversations/' + conv + '/title', {'title': '语义修复验收-报表增减'}, 'PUT')
        conv, events = turn(conv, '再加上应收报表')
        p = payload(events, 'preview')
        check('Append retains sales and adds receivables', p and {r['reportId'] for r in p['byReport']} == {'rpt-sales-order', 'rpt-ar-invoice'})
        conv, events = turn(conv, '移除销售报表')
        p = payload(events, 'preview')
        check('Remove retains only receivables', p and {r['reportId'] for r in p['byReport']} == {'rpt-ar-invoice'})
        conv, events = turn(conv, '改为所有报表')
        p = payload(events, 'preview')
        check('Clear restores all dispatchable reports', p and {r['reportId'] for r in p['byReport']} == {'rpt-sales-order', 'rpt-ar-invoice', 'rpt-expense-claim'})
        if options.resume_conversation:
            _, events = turn(options.resume_conversation, 'A公司销售报表的')
            check('Previously failed persisted conversation recovers', a_preview(events))
    except Exception as error:
        evidence['error'] = str(error)
        check('Acceptance completed without exception', False)
    finally:
        for plan_id in created_plans:
            try:
                current = request('dispatch/plans/' + plan_id)
                if current['status'] == 'PENDING': request('dispatch/plans/' + plan_id + '/cancel', {}, 'POST')
            except Exception as error:
                check('Cleanup of pending plan ' + plan_id, False)
        evidence['passed'] = sum(c['passed'] for c in evidence['checks'])
        evidence['total'] = len(evidence['checks'])
        out = Path(options.output)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({k: evidence[k] for k in ('passed', 'total', 'conversations', 'confirmedDispatches')}, ensure_ascii=False))
    raise SystemExit(0 if evidence['passed'] == evidence['total'] else 1)


if __name__ == '__main__':
    main()
