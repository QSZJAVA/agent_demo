const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const payload = JSON.parse(fs.readFileSync('backend/target/review-20260930-large-id.json','utf8'));
const selected = payload.data[0];
const direct = JSON.stringify({reportType:'sales',ids:[selected.id]});
assert.equal(selected.id,'9007199254740993');
assert.ok(direct.includes('"9007199254740993"'));
console.log('B1 selected original=9007199254740993, direct request='+direct);

const source = fs.readFileSync('frontend/src/api/http.js','utf8')
  .replace(/^import .*$/gm,'').replace('export default http','');
let responseError;
let currentToken = 'new-session';
let expiryCalls = 0;
vm.runInNewContext(source,{
  axios:{create:()=>({interceptors:{request:{use:()=>{}},response:{use:(ok,err)=>{responseError=err;}}}})},
  Message:{error:()=>{}},authHeaders:()=>({Authorization:'Bearer '+currentToken}),getSessionToken:()=>currentToken,
  sessionExpired:expected=>{if(currentToken===expected){currentToken=null;expiryCalls++;}},
});
(async()=>{
  await responseError({response:{status:401},config:{sessionToken:'old-session',headers:{Authorization:'Bearer old-session'}},message:'old request'}).catch(()=>{});
  assert.equal(currentToken,'new-session');assert.equal(expiryCalls,0);
  console.log('B4 old-session 401 leaves new-session intact, expiryCalls='+expiryCalls);
})();
