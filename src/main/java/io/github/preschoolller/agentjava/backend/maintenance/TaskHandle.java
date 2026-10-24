package io.github.preschoolller.agentjava.backend.maintenance;

/** 在册后台/前台进程的句柄：输出缓冲（读流线程写、getBufferedOutput/快照读，StringBuffer
 *  线程安全）+ 进程 + 是否后台任务（ShutDownJob.kill 的调用方据此决定终止后的编排走
 *  bg-killed 流程还是让前台工具调用自行收尾） */
public record TaskHandle(StringBuffer output, Process process, boolean background) {
}
