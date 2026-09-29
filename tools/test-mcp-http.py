"""Live-model acceptance for the local MCP stack. --confirm-dispatch mutates ONE demo row.

Use only with the dedicated report_mcp demo database. Credentials and session tokens
are read locally and are never included in the evidence file or console output.
"""
import argparse
import json
import secrets
import time
import urllib.request
import urllib.error
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--mcp-url', default='http://127.0.0.1:8090/mcp')
    parser.add_argument('--credentials', default='.runtime/mcp-credentials.json')
    parser.add_argument('--output', default='.runtime/mcp-http-acceptance.json')
    parser.add_argument('--confirm-dispatch', action='store_true')
    options = parser.parse_args()
    evidence = {'checks': [], 'turns': [], 'confirmedDispatches': 0}
    credentials = json.loads(Path(options.credentials).read_text(encoding='utf-8-sig'))
    token = None
    admin_token = None
    account = 'mcp_test_' + uuid.uuid4().hex[:12]
    account_form = {'userId': account, 'displayName': 'MCP 联调验收', 'password': secrets.token_urlsafe(24),
                    'companies': ['A'], 'permissions': ['report:sales'], 'admin': False, 'enabled': True}

    def raw(url, body=None, method=None, headers=None):
        request = urllib.request.Request(url, data=None if body is None else json.dumps(body, ensure_ascii=False).encode(),
                                        method=method, headers={'Content-Type': 'application/json', **(headers or {})})
        try:
            with urllib.request.urlopen(request, timeout=190) as response:
                return response.status, response.headers.get('Content-Type', ''), response.read().decode()
        except urllib.error.HTTPError as error:
            return error.code, error.headers.get('Content-Type', ''), error.read().decode()

    def api(path, body=None, method=None, session=None, extras=None, unwrap=True):
        auth = session if session is not None else token
        headers = {**({'Authorization': 'Bearer ' + auth} if auth else {}), **(extras or {})}
        status, content_type, text = raw(options.base_url + '/api/' + path, body, method, headers)
        if 'text/event-stream' in content_type:
            events = []
            for block in text.replace('\r\n', '\n').split('\n\n'):
                values = dict(line.split(':', 1) for line in block.splitlines() if ':' in line)
                if 'event' in values and 'data' in values:
                    events.append({'type': values['event'].strip(), 'data': json.loads(values['data'].strip())})
            return events
        value = json.loads(text)
        if not unwrap:
            return status, value
        if status != 200 or value.get('code', 0) != 0:
            raise RuntimeError(value.get('message', 'API failed'))
        return value.get('data', value)

    def check(name, passed):
        evidence['checks'].append({'name': name, 'passed': bool(passed)})
        if not passed:
            raise AssertionError(name)

    def turn(conversation, message, **selection):
        start = time.monotonic()
        events = api('agent/chat', {'conversationId': conversation, 'message': message, **selection})
        evidence['turns'].append({'message': message, 'events': events, 'latencyMs': round((time.monotonic() - start) * 1000)})
        check('真实模型 SSE 完成：' + message, any(e['type'] == 'done' for e in events) and not any(e['type'] == 'error' for e in events))
        conv = next((e['data']['conversationId'] for e in events if e['type'] == 'conversation'), conversation)
        return conv, events

    try:
        status, _, _ = raw(options.base_url + '/api/report/sales/page', headers={'X-User-Id': 'admin'})
        check('伪造演示身份不能访问受保护接口', status == 401)
        status, _, _ = raw(options.mcp_url, {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list'})
        check('MCP 拒绝没有服务凭据的请求', status == 401)
        admin_token = api('auth/login', {'userId': 'admin', 'password': credentials['adminPassword']})['token']
        api('auth/users', account_form, 'PUT', session=admin_token)
        token = api('auth/login', {'userId': account, 'password': account_form['password']})['token']
        me = api('auth/me', extras={'X-User-Id': 'admin'})
        check('登录身份覆盖伪造用户头', me['userId'] == account and not me['admin'])
        page = api('report/sales/page?page=1&size=2')
        check('分页只返回权限内 A 公司记录', len(page['records']) == 2 and all(r['companyCode'] == 'A' for r in page['records']))
        _, denied = api('report/receivable/page', unwrap=False)
        check('没有应收报表权限时拒绝查询', denied['code'] in (403, 404))
        model = api('agent/model')
        evidence['model'] = model
        check('运行真实 DeepSeek 模型', 'deepseek-v4.1-flash' in str(model))

        conversation, events = turn(None, '查询A公司销售报表中可以派单的记录')
        preview = next((e['data'] for e in events if e['type'] == 'preview'), None)
        check('真实模型查询经 MCP 返回销售候选', preview is not None and preview['total'] > 0
              and all(r['reportId'] == 'rpt-sales-order' and r['companyCode'] == 'A' for r in preview['records']))
        selected = preview['records'][0]
        # The existing intent contract represents exclusions. Use the real UI selection
        # contract to select one row, then ask the real model to prepare that selection.
        exclusions = [{'reportId': r['reportId'], 'recordId': r['recordId']} for r in preview['records']
                      if r['recordId'] != selected['recordId']]
        conversation, events = turn(conversation, '把当前勾选的记录生成派单清单',
                                    previewId=preview['previewId'], excludedRecords=exclusions)
        plan = next((e['data'] for e in events if e['type'] == 'plan'), None)
        check('真实模型只生成指定一条记录的待确认清单', plan is not None and plan['count'] == 1
              and plan['records'][0]['recordId'] == selected['recordId'] and plan['status'] == 'PENDING')
        evidence['conversationId'] = conversation
        evidence['planId'] = plan['planId']
        page = api('report/sales/page?page=1&size=200')
        row = next(r for r in page['records'] if str(r['id']) == selected['recordId'])
        check('模型生成清单后尚未执行派单', row['dispatchStatus'] == 0)

        if options.confirm_dispatch:
            result = api('dispatch/plans/' + plan['planId'] + '/confirm', {})
            check('确认经 MCP 原子完成一条派单', result['successCount'] == 1 and result['failedCount'] == 0)
            evidence['confirmedDispatches'] = result['successCount']
            evidence['result'] = result
            replay = api('dispatch/plans/' + plan['planId'] + '/confirm', {})
            check('重复确认返回原结果', replay['replayed'] and replay['successCount'] == 1)
            items = api('dispatch/plans/' + plan['planId'] + '/items')
            request_id = items[0]['externalRequestId']
            _, _, result_text = raw(options.mcp_url, {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/call',
                'params': {'name': 'dispatch_lookup', 'arguments': {'tenantId': 'T001', 'operatorId': account, 'requestId': request_id}}},
                headers={'Authorization': 'Bearer ' + credentials['serviceToken'], 'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-11-25'})
            lookup = json.loads(result_text)['result']
            business_result = json.loads(lookup['content'][0]['text'])['data']
            check('业务服务可按稳定请求号查回成功', business_result['status'] == 'SUCCESS')
            page = api('report/sales/page?page=1&size=200')
            check('报表重新查询显示已派单', next(r for r in page['records'] if str(r['id']) == selected['recordId'])['dispatchStatus'] == 1)
            evidence['externalRequestId'] = request_id
        else:
            api('dispatch/plans/' + plan['planId'] + '/cancel', {})
        api('auth/logout', {})
        status, _ = api('auth/me', unwrap=False)
        check('退出登录立即撤销会话', status == 401)
    finally:
        if admin_token:
            try:
                api('auth/users', {**account_form, 'password': None, 'enabled': False}, 'PUT', session=admin_token)
                api('auth/logout', {}, session=admin_token)
            except Exception:
                evidence['cleanupWarning'] = '测试账号禁用或管理员会话退出失败，请检查服务状态'
        output = Path(options.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'passed': sum(c['passed'] for c in evidence['checks']), 'checks': len(evidence['checks']),
                          'confirmedDispatches': evidence['confirmedDispatches'], 'evidence': str(output)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
