/** 最终演示基线检查：默认配置、文档链接、运行入口与生成图表必须保持一致。 */
const fs=require('node:fs'),path=require('node:path'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..');
const read=p=>fs.readFileSync(path.join(root,p),'utf8').replace(/^\uFEFF/,'');
const baseline=JSON.parse(read('demo-baseline.json'));
const problems=[];
const need=(ok,message)=>{if(!ok)problems.push(message)};
need(baseline.semanticVersion===1 && baseline.profiles==='real,mcp' && baseline.semanticMode==='active' && baseline.nativeSchema===true,'Unexpected final semantic baseline');
need(JSON.parse(read(baseline.schema)).properties.version.enum[0]===baseline.semanticVersion,'Schema version differs from baseline');
need(baseline.database==='report_demo','Demo database name must remain report_demo');
need(baseline.releaseVersion==='1.0.0','Unexpected application release version');
need(read('backend/src/main/java/com/example/report/semantic/SemanticIntent.java').includes(`VERSION = ${baseline.semanticVersion};`),'Java protocol version differs from baseline');
need(!fs.existsSync(path.join(root,'backend/src/main/resources/semantic/intent-v4.schema.json')),'Retired V4 protocol resource still active');
for(const file of ['pom.xml','backend/pom.xml','business-service/pom.xml'])need(read(file).includes(`<version>${baseline.releaseVersion}</version>`) && !read(file).includes('<version>2.0.0</version>'),`${file}: application version differs`);
for(const file of ['tools/start-app.ps1','tools/start-mcp.ps1','tools/stop-mcp.ps1','backend/Dockerfile','business-service/Dockerfile','docker-compose.yml'])need(!read(file).includes('2.0.0'),`${file}: retired package name remains`);
for(const file of ['tools/start-app.ps1','tools/start-mcp.ps1','tools/prepare-demo.ps1'])need(read(file).includes('demo-baseline.json'),`${file}: baseline not loaded`);
for(const file of ['.env.example','docker-compose.yml','tools/env.local.example.cmd','backend/src/main/resources/application.yml','business-service/src/main/resources/application.yml'])need(read(file).includes(baseline.database),`${file}: database differs from baseline`);
for(const file of ['.env.example','docker-compose.yml','tools/env.local.example.cmd','backend/src/main/resources/application.yml','business-service/src/main/resources/application.yml'])need(!/report_demo_[A-Za-z0-9_]+/.test(read(file)),`${file}: renamed Demo database is not allowed`);
for(const file of ['tools/start-real.ps1','tools/start-backend.cmd','tools/start-frontend.cmd','tools/test-semantic-http.py'])need(!fs.existsSync(path.join(root,file)),`Retired entry point still active: ${file}`);
need(!read('tools/start-mcp.ps1').includes('$Mock'),'Alternate launch mode returned');
need(!read('backend/src/main/java/com/example/report/agent/AgentChatService.java').includes('legacyChat'),'Retired chat route returned');
need(!fs.existsSync(path.join(root,'backend/src/main/java/com/example/report/agent/AgentConfig.java')),'Retired model tool client returned');
const walk=dir=>fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(dir,e.name)):[path.join(dir,e.name)]);
// 历史文件可以保留旧版本文字，但其Markdown引用也应能定位原证据或归档文件。
const markdown=[path.join(root,'README.md'),...walk(path.join(root,'docs')).filter(p=>p.endsWith('.md'))];
for(const file of markdown) {
    const text=fs.readFileSync(file,'utf8').replace(/```[\s\S]*?```/g,'');
    for(const match of text.matchAll(/\]\(([^\n)]+)\)/g)) {
        let target=match[1].replace(/^<|>$/g,'').split('#')[0];
        if(!target || /^(?:[a-zA-Z]+:|\/)/.test(target) || target.includes(' '))continue;
        try{target=decodeURIComponent(target)}catch{}
        need(fs.existsSync(path.resolve(path.dirname(file),target)),`${path.relative(root,file)}: broken link ${target}`);
    }
}
const generated=spawnSync(process.execPath,[path.join(root,'docs/diagrams/build-semantic-flows.cjs'),'--check'],{encoding:'utf8'});
need(generated.status===0,'Generated diagrams differ from generator: '+(generated.stderr||generated.stdout));
if(problems.length){process.stderr.write(problems.join('\n')+'\n');process.exitCode=1}else console.log(`Final V1 Demo baseline passed; ${markdown.length} Markdown files and 8 generated flows checked.`);
