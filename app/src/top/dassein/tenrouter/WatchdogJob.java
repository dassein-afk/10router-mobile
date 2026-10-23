package top.dassein.tenrouter;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** JobScheduler 守护：周期检查网关是否真的在服务，不在就拉起来。 */
public class WatchdogJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        if (KeepAlive.userStopped(this)) return false;
        if (!KeepAlive.isUp()) KeepAlive.ensureRunning(this);
        KeepAlive.schedulePing(this);
        return false;   // 同步完成
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;   // 不重排：周期性作业自己会再来，返回 true 会在被打断时形成重排风暴
    }
}
