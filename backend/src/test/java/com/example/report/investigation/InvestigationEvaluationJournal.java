package com.example.report.investigation;

import com.example.report.common.JsonUtil;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 真实评估的当前格式检查点；调用前固定配置和计划，完成一题即原子保存，未核实的在途题次禁止自动重放。 */
final class InvestigationEvaluationJournal implements AutoCloseable {
    final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private FileChannel sourceChannel;
    private FileLock sourceLock;
    private final Map<String,Object> manifest;
    private final Set<String> planned,started=new LinkedHashSet<>();
    private final Map<String,Map<String,Object>> completed=new LinkedHashMap<>();
    private boolean closed;

    /** 在任何模型调用前创建独立归档；同目录仅允许一个写者，原批次证据不被续跑改写。 */
    InvestigationEvaluationJournal(Path root,Map<String,Object> manifest,Set<String> planned,List<Map<String,Object>> prior) throws Exception {
        this.directory=root.resolve("run-"+Instant.now().toString().replace(':','-')+"-"+UUID.randomUUID().toString().substring(0,8));
        Files.createDirectories(directory);channel=FileChannel.open(directory.resolve("writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);lock=channel.lock();
        this.manifest=new LinkedHashMap<>(manifest);this.manifest.put("archive",directory.getFileName().toString());this.manifest.put("journalVersion",2);this.planned=new LinkedHashSet<>(planned);
        try {
            for(var run:prior) {String key=key(run);if(!planned.contains(key) || completed.putIfAbsent(key,run)!=null) throw new IllegalArgumentException("恢复结果不属于计划或题次重复");started.add(key);}
            atomic(directory.resolve("manifest.json"),this.manifest);checkpoint();
        } catch(Exception failure) {close();throw failure;}
    }
    /**
     * 取得父归档独占执行权后校验配置并创建续跑；锁随子批次持有到关闭。
     * 子批次调用前在父目录原子登记唯一后继，之后只能从该后继继续，防止串行重用父归档重放题次。
     * 原manifest和checkpoint不改写；登记失败不返回可执行子批次，也不会发出模型调用。
     */
    static InvestigationEvaluationJournal resume(Path root,Path archive,Map<String,Object> manifest,Set<String> planned) throws Exception {
        var channel=FileChannel.open(archive.resolve("writer.lock"),StandardOpenOption.WRITE);
        FileLock held=null;InvestigationEvaluationJournal child=null;
        try {
            held=acquire(channel);requireUndelegated(archive);
            // 配置中的价格必须保持十进制精度，否则相同价格也可能被误判为配置变化。
            var prior=readJson(archive.resolve("manifest.json"));
            InvestigationEvaluation.requireResumeManifest(prior,manifest);
            var runs=readCheckpoint(archive,planned);
            var childManifest=new LinkedHashMap<>(manifest);childManifest.put("resumedFrom",archive.toAbsolutePath().normalize().toString());
            child=new InvestigationEvaluationJournal(root,childManifest,planned,runs);
            atomic(archive.resolve("continuation.json"),Map.of("archive",child.directory.toAbsolutePath().normalize().toString(),"createdAt",Instant.now().toString()));
            child.sourceChannel=channel;child.sourceLock=held;
            return child;
        } catch(Exception failure) {
            if(child!=null) try {child.close();} catch(Exception closeFailure) {failure.addSuppressed(closeFailure);}
            try {if(held!=null) held.release();} finally {channel.close();}
            throw failure;
        }
    }
    /** 已登记后继的父归档不再分叉；即使前次已关闭或子批次调用中断也不能绕开其STARTED记录。 */
    private static void requireUndelegated(Path archive) throws Exception {
        if(Files.exists(archive.resolve("continuation.json")))
            throw new IllegalStateException("原归档已创建续跑，请使用后继归档核查或继续："+JsonUtil.toMap(Files.readString(archive.resolve("continuation.json"))).get("archive"));
    }
    private static FileLock acquire(FileChannel channel) throws Exception {
        FileLock held;
        try {held=channel.tryLock();} catch(OverlappingFileLockException busy) {throw new IllegalStateException("原评估或续跑仍在运行，不能同时续跑");}
        if(held==null) throw new IllegalStateException("原评估或续跑仍在运行，不能同时续跑");
        return held;
    }
    /** STARTED先于真实调用落盘；已开始的题次不重复执行，包括上次尚未保存结果的题次。 */
    synchronized void start(String key) throws Exception {
        if(closed) throw new IllegalStateException("评估归档已关闭");
        if(!planned.contains(key) || !started.add(key)) throw new IllegalArgumentException("题次不属于计划或已开始："+key);
        checkpoint();
    }
    /** 每题结束即保存完整结果，不等待同批较慢题次；失败与成功都只能写入一次。 */
    synchronized void complete(String key,Map<String,Object> run) throws Exception {
        if(closed) throw new IllegalStateException("评估归档已关闭，迟到结果不能覆盖检查点");
        if(!started.contains(key) || !key.equals(key(run)) || completed.putIfAbsent(key,run)!=null) throw new IllegalArgumentException("完成结果未开始、题次不符或重复");
        checkpoint();
    }
    synchronized List<Map<String,Object>> results() {return new ArrayList<>(completed.values());}
    /** 只有计划内同场景的全部题次均完成且通过才计数，不能把中断后的单次通过称为三次全通过。 */
    synchronized long fullyPassedCases() {
        return planned.stream().map(k -> k.substring(0,k.lastIndexOf(':'))).distinct()
                .filter(id -> planned.stream().filter(k -> k.startsWith(id+":")).allMatch(k -> completed.containsKey(k) && Boolean.TRUE.equals(completed.get(k).get("passed")))).count();
    }
    Map<String,Object> manifest() {return new LinkedHashMap<>(manifest);}
    /** 最终清单同样原子替换，收尾中断不能破坏调用前已保存的配置依据。 */
    synchronized void finishManifest(Map<String,Object> finished) throws Exception {
        if(closed) throw new IllegalStateException("评估归档已关闭");
        atomic(directory.resolve("manifest.json"),finished);
    }
    private void checkpoint() throws Exception {atomic(directory.resolve("checkpoint.json"),Map.of("planned",planned,"started",started,"runs",completed.values()));}
    private static String key(Map<String,Object> run) {return run.get("caseId")+":"+run.get("repeat");}
    /** 只读检查入口，不授予续跑执行权；真正续跑必须用resume持有父锁并登记后继。 */
    static List<Map<String,Object>> readCompleted(Path archive,Set<String> planned) throws Exception {
        try(var channel=FileChannel.open(archive.resolve("writer.lock"),StandardOpenOption.WRITE)) {
            try(var held=acquire(channel)) {requireUndelegated(archive);return readCheckpoint(archive,planned);}
        }
    }
    /** 调用者须已持有归档锁；缺失、重复或未完成题次一律拒绝，不猜测调用结果和费用。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> readCheckpoint(Path archive,Set<String> planned) throws Exception {
                Map<String,Object> state=readJson(archive.resolve("checkpoint.json"));
                if(!planned.equals(new HashSet<>((List<String>)state.get("planned")))) throw new IllegalStateException("恢复计划集合不一致");
                var started=new HashSet<>((List<String>)state.get("started"));var done=new HashSet<String>();
                var runs=(List<Map<String,Object>>)state.get("runs");
                for(var run:runs) {
                    if(!done.add(key(run)) || !planned.contains(key(run)) || !Objects.toString(run.get("runId"),"").matches("[a-f0-9-]{36}")) throw new IllegalStateException("检查点结果身份无效");
                }
                if(!started.equals(done)) throw new IllegalStateException("存在已开始但未保存结果的题次，调用结果未知，禁止自动重放："+started.stream().filter(k -> !done.contains(k)).toList());
                return runs;
    }
    /** 配置和结果使用相同十进制读取口径，不通过double转换价格或费用。 */
    @SuppressWarnings("unchecked")
    private static Map<String,Object> readJson(Path file) throws Exception {
        return JsonUtil.MAPPER.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(Files.readString(file),Map.class);
    }
    /** 同目录临时文件与原子替换避免半份JSON被当作已完成记录；不支持原子替换时直接失败。 */
    private static void atomic(Path path,Object value) throws Exception {
        Path temporary=path.resolveSibling(path.getFileName()+".tmp");
        byte[] bytes=JsonUtil.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(var out=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) out.write(buffer);out.force(true);
        }
        Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    @Override public synchronized void close() throws Exception {
        if(closed) return;closed=true;
        try {try {lock.release();} finally {channel.close();}}
        finally {if(sourceChannel!=null) try {sourceLock.release();} finally {sourceChannel.close();}}
    }
}
