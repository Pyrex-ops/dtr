import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  ChevronLeft,
  ChevronRight,
  CircleStop,
  Eye,
  LoaderCircle,
  Pause,
  Play,
  Plus,
  RefreshCw,
  Send,
  Square,
  Trash2,
  X,
} from 'lucide-react';
import './styles.css';

const API_BASE = window.__DTR_CONFIG__?.API_BASE ?? import.meta.env.VITE_API_BASE ?? 'http://localhost:8080/api/render';
const CHUNKS_PER_PAGE = 8;

const emptyForm = {
  inputFilePath: '',
  totalFrames: 100,
  command: '',
  args: '',
  outputPath: '/tmp/dtr/job_{jobId}_frame_{frame}.png',
};

function App() {
  const [jobs, setJobs] = useState([]);
  const [chunksByJob, setChunksByJob] = useState({});
  const [selectedJobId, setSelectedJobId] = useState(null);
  const [form, setForm] = useState(emptyForm);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [deletingJobId, setDeletingJobId] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [streamStatus, setStreamStatus] = useState('connecting');
  const [activity, setActivity] = useState([]);
  const [error, setError] = useState('');
  const [chunkPage, setChunkPage] = useState(1);
  const [previewChunk, setPreviewChunk] = useState(null);

  const selectedJob = useMemo(
    () => jobs.find((job) => job.id === selectedJobId) ?? jobs[0] ?? null,
    [jobs, selectedJobId],
  );
  const selectedChunks = selectedJob ? chunksByJob[selectedJob.id] ?? [] : [];
  const chunkPageCount = Math.max(1, Math.ceil(selectedChunks.length / CHUNKS_PER_PAGE));
  const pagedChunks = selectedChunks.slice((chunkPage - 1) * CHUNKS_PER_PAGE, chunkPage * CHUNKS_PER_PAGE);
  const metrics = useMemo(() => getMetrics(jobs, chunksByJob), [jobs, chunksByJob]);

  useEffect(() => {
    setChunkPage(1);
    setPreviewChunk(null);
  }, [selectedJobId]);

  useEffect(() => {
    setChunkPage((page) => Math.min(page, chunkPageCount));
  }, [chunkPageCount]);

  async function refresh() {
    setError('');
    setIsLoading(true);
    try {
      const nextJobs = await apiGet('/jobs');
      nextJobs.sort((a, b) => b.id - a.id);
      setJobs(nextJobs);
      setSelectedJobId((currentJobId) => {
        if (currentJobId && nextJobs.some((job) => job.id === currentJobId)) {
          return currentJobId;
        }
        return nextJobs[0]?.id ?? null;
      });
      const entries = await Promise.all(
        nextJobs.map(async (job) => [job.id, await apiGet(`/jobs/${job.id}/chunks`)]),
      );
      setChunksByJob(Object.fromEntries(entries));
    } catch (err) {
      setError(err.message);
    } finally {
      setIsLoading(false);
    }
  }

  useEffect(() => {
    refresh();
  }, []);

  useEffect(() => {
    const stream = new EventSource(`${API_BASE}/stream`);
    stream.onopen = () => setStreamStatus('live');
    stream.onerror = () => setStreamStatus('reconnecting');
    stream.addEventListener('job_update', (event) => {
      const update = parseUpdate(event.data);
      setActivity((items) => [update, ...items].slice(0, 9));
      refresh();
    });
    return () => stream.close();
  }, []);

  async function submitJob(event) {
    event.preventDefault();
    setError('');
    setIsSubmitting(true);
    try {
      const body = new URLSearchParams();
      body.set('inputFilePath', form.inputFilePath.trim());
      body.set('totalFrames', String(form.totalFrames));
      body.set('command', form.command.trim());
      if (form.outputPath.trim()) body.set('outputPath', form.outputPath.trim());
      splitArgs(form.args).forEach((arg) => body.append('args', arg));

      const response = await fetch(`${API_BASE}/submit-job`, {
        method: 'POST',
        body,
      });
      if (!response.ok) throw new Error(`Submit failed with ${response.status}`);
      const jobId = Number(await response.text());
      setForm(emptyForm);
      setSelectedJobId(jobId);
      await refresh();
    } catch (err) {
      setError(err.message);
    } finally {
      setIsSubmitting(false);
    }
  }

  async function controlJob(jobId, action) {
    setError('');
    try {
      const response = await fetch(`${API_BASE}/${jobId}/${action}`, { method: 'POST' });
      if (!response.ok) throw new Error(`${action} failed with ${response.status}`);
      await refresh();
    } catch (err) {
      setError(err.message);
    }
  }

  async function deleteJob(jobId) {
    if (!window.confirm(`Delete job #${jobId}? This removes its chunks and rendered frames.`)) {
      return;
    }

    setError('');
    setDeletingJobId(jobId);
    try {
      const response = await fetch(`${API_BASE}/jobs/${jobId}`, { method: 'DELETE' });
      if (!response.ok) throw new Error(`Delete failed with ${response.status}`);
      setSelectedJobId(null);
      await refresh();
    } catch (err) {
      setError(err.message);
    } finally {
      setDeletingJobId(null);
    }
  }

  return (
    <main className="app-shell">
      <section className="topbar">
        <div>
          <p className="eyebrow">Distributed Task Runner</p>
          <h1>Render Console</h1>
        </div>
        <div className="topbar-actions">
          <span className={`stream-pill ${streamStatus}`}>
            <span />
            {streamStatus}
          </span>
          <button className="icon-button" onClick={refresh} title="Refresh jobs">
            <RefreshCw size={18} />
          </button>
        </div>
      </section>

      {error && <div className="error-banner">{error}</div>}

      <section className="metrics-grid">
        <Metric label="Jobs" value={jobs.length} />
        <Metric label="Frames Done" value={metrics.framesDone} />
        <Metric label="Active Chunks" value={metrics.activeChunks} />
        <Metric label="Queued Chunks" value={metrics.pendingChunks} />
      </section>

      <section className="workspace">
        <aside className="panel submit-panel">
          <div className="panel-title">
            <Plus size={18} />
            <h2>Submit Job</h2>
          </div>
          <form onSubmit={submitJob} className="job-form">
            <label>
              Input file path
              <input
                required
                value={form.inputFilePath}
                onChange={(event) => setForm({ ...form, inputFilePath: event.target.value })}
                placeholder="/projects/input.dat"
              />
            </label>
            <label>
              Total frames
              <input
                required
                min="1"
                type="number"
                value={form.totalFrames}
                onChange={(event) => setForm({ ...form, totalFrames: event.target.value })}
              />
            </label>
            <label>
              Command
              <input
                required
                value={form.command}
                onChange={(event) => setForm({ ...form, command: event.target.value })}
                placeholder="python"
              />
            </label>
            <label>
              Args
              <textarea
                rows="4"
                value={form.args}
                onChange={(event) => setForm({ ...form, args: event.target.value })}
                placeholder="render.py --input {input} --frame {frame} --output {output}"
              />
            </label>
            <label>
              Output path pattern
              <input
                required
                value={form.outputPath}
                onChange={(event) => setForm({ ...form, outputPath: event.target.value })}
                placeholder="/tmp/dtr/job_{jobId}_frame_{frame}.png"
              />
            </label>
            <button className="primary-button" type="submit" disabled={isSubmitting}>
              {isSubmitting ? <LoaderCircle className="spin" size={18} /> : <Send size={18} />}
              Submit
            </button>
          </form>
        </aside>

        <section className="panel jobs-panel">
          <div className="panel-title">
            <Square size={18} />
            <h2>Jobs</h2>
          </div>
          {isLoading && jobs.length === 0 ? (
            <div className="empty-state">Loading jobs...</div>
          ) : jobs.length === 0 ? (
            <div className="empty-state">No jobs submitted yet.</div>
          ) : (
            <div className="job-list">
              {jobs.map((job) => {
                const chunks = chunksByJob[job.id] ?? [];
                const progress = getProgress(job, chunks);
                return (
                  <button
                    className={`job-row ${selectedJob?.id === job.id ? 'selected' : ''}`}
                    key={job.id}
                    onClick={() => setSelectedJobId(job.id)}
                  >
                    <div className="row-main">
                      <strong>Job #{job.id}</strong>
                      <span>{job.inputFilePath}</span>
                    </div>
                    <div className="row-side">
                      <StatusBadge status={job.status} />
                      <span>{progress}%</span>
                    </div>
                    <div className="progress-track">
                      <div style={{ width: `${progress}%` }} />
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </section>

        <section className="panel detail-panel">
          {selectedJob ? (
            <>
              <div className="detail-header">
                <div>
                  <p className="eyebrow">Selected</p>
                  <h2>Job #{selectedJob.id}</h2>
                </div>
                <StatusBadge status={selectedJob.status} />
              </div>

              <div className="detail-grid">
                <Detail label="Input file" value={selectedJob.inputFilePath} />
                <Detail label="Total frames" value={selectedJob.totalFrames} />
                <Detail label="Chunks" value={selectedChunks.length} />
                <Detail label="Progress" value={`${getProgress(selectedJob, selectedChunks)}%`} />
              </div>

              <div className="control-row">
                <button onClick={() => controlJob(selectedJob.id, 'pause')}>
                  <Pause size={16} />
                  Pause
                </button>
                <button onClick={() => controlJob(selectedJob.id, 'resume')}>
                  <Play size={16} />
                  Resume
                </button>
                <button className="danger-button" onClick={() => controlJob(selectedJob.id, 'stop')}>
                  <CircleStop size={16} />
                  Stop
                </button>
                {isDeletableJob(selectedJob) && (
                  <button
                    className="delete-button"
                    onClick={() => deleteJob(selectedJob.id)}
                    disabled={deletingJobId === selectedJob.id}
                  >
                    {deletingJobId === selectedJob.id ? (
                      <LoaderCircle className="spin" size={16} />
                    ) : (
                      <Trash2 size={16} />
                    )}
                    Delete
                  </button>
                )}
              </div>

              <div className="chunks-header">
                <strong>Chunks</strong>
                <div className="pager">
                  <button
                    className="icon-button"
                    onClick={() => setChunkPage((page) => Math.max(1, page - 1))}
                    disabled={chunkPage === 1}
                    title="Previous chunks"
                  >
                    <ChevronLeft size={16} />
                  </button>
                  <span>{chunkPage} / {chunkPageCount}</span>
                  <button
                    className="icon-button"
                    onClick={() => setChunkPage((page) => Math.min(chunkPageCount, page + 1))}
                    disabled={chunkPage === chunkPageCount}
                    title="Next chunks"
                  >
                    <ChevronRight size={16} />
                  </button>
                </div>
              </div>

              <div className="chunks">
                {pagedChunks.map((chunk) => (
                  <div className="chunk-row" key={chunk.id}>
                    <span>#{chunk.id}</span>
                    <strong>{chunk.startFrame}-{chunk.endFrame}</strong>
                    <StatusBadge status={chunk.status} />
                    <small>{chunk.assignedNode ?? 'unassigned'}</small>
                    <button
                      className="preview-button"
                      onClick={() => setPreviewChunk(chunk)}
                      disabled={chunk.status !== 'COMPLETED'}
                      title={chunk.status === 'COMPLETED' ? 'Preview files' : 'Preview available when completed'}
                    >
                      <Eye size={15} />
                      Preview
                    </button>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <div className="empty-state">Select a job to inspect chunks.</div>
          )}
        </section>
      </section>

      <section className="panel activity-panel">
        <div className="panel-title">
          <LoaderCircle size={18} />
          <h2>Live Activity</h2>
        </div>
        {activity.length === 0 ? (
          <div className="empty-state">Waiting for server events.</div>
        ) : (
          <div className="activity-list">
            {activity.map((item, index) => (
              <div className="activity-row" key={`${item.receivedAt}-${index}`}>
                <span>{item.event ?? 'UPDATE'}</span>
                <strong>Job #{item.jobId ?? '-'}</strong>
                <small>{item.frame ? `Frame ${item.frame}` : item.status ?? ''}</small>
              </div>
            ))}
          </div>
        )}
      </section>

      {selectedJob && previewChunk && (
        <PreviewModal
          key={previewChunk.id}
          chunk={previewChunk}
          jobId={selectedJob.id}
          onClose={() => setPreviewChunk(null)}
        />
      )}
    </main>
  );
}

function PreviewModal({ chunk, jobId, onClose }) {
  const frames = getChunkFrames(chunk);
  const [frame, setFrame] = useState(frames[0]);
  const frameIndex = frames.indexOf(frame);
  const fileUrl = `${API_BASE}/jobs/${jobId}/frames/${frame}`;

  useEffect(() => {
    setFrame(frames[0]);
  }, [chunk.id]);

  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
      <section className="preview-modal" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <div>
            <p className="eyebrow">Chunk #{chunk.id}</p>
            <h2>Frames {chunk.startFrame}-{chunk.endFrame}</h2>
          </div>
          <button className="icon-button" onClick={onClose} title="Close preview">
            <X size={18} />
          </button>
        </div>

        <div className="preview-stage">
          <iframe src={fileUrl} title={`Frame ${frame}`} />
        </div>

        <div className="preview-footer">
          <button
            className="icon-button"
            onClick={() => setFrame(frames[Math.max(0, frameIndex - 1)])}
            disabled={frameIndex <= 0}
            title="Previous frame"
          >
            <ChevronLeft size={16} />
          </button>
          <span>Frame {frame}</span>
          <button
            className="icon-button"
            onClick={() => setFrame(frames[Math.min(frames.length - 1, frameIndex + 1)])}
            disabled={frameIndex >= frames.length - 1}
            title="Next frame"
          >
            <ChevronRight size={16} />
          </button>
        </div>
      </section>
    </div>
  );
}

function Metric({ label, value }) {
  return (
    <div className="metric-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function Detail({ label, value }) {
  return (
    <div className="detail-item">
      <span>{label}</span>
      <strong title={String(value)}>{value}</strong>
    </div>
  );
}

function StatusBadge({ status }) {
  return <span className={`status-badge ${String(status).toLowerCase()}`}>{status}</span>;
}

async function apiGet(path) {
  const response = await fetch(`${API_BASE}${path}`);
  if (!response.ok) throw new Error(`${path} failed with ${response.status}`);
  return response.json();
}

function parseUpdate(data) {
  try {
    return { ...JSON.parse(data), receivedAt: Date.now() };
  } catch {
    return { event: data, receivedAt: Date.now() };
  }
}

function splitArgs(value) {
  return value
    .split(/\s+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function getProgress(job, chunks) {
  if (!job?.totalFrames) return 0;
  const completed = chunks
    .filter((chunk) => chunk.status === 'COMPLETED')
    .reduce((total, chunk) => total + chunk.endFrame - chunk.startFrame + 1, 0);
  return Math.min(100, Math.round((completed / job.totalFrames) * 100));
}

function getMetrics(jobs, chunksByJob) {
  return jobs.reduce(
    (totals, job) => {
      const chunks = chunksByJob[job.id] ?? [];
      totals.framesDone += chunks
        .filter((chunk) => chunk.status === 'COMPLETED')
        .reduce((sum, chunk) => sum + chunk.endFrame - chunk.startFrame + 1, 0);
      totals.activeChunks += chunks.filter((chunk) => chunk.status === 'IN_PROGRESS').length;
      totals.pendingChunks += chunks.filter((chunk) => chunk.status === 'PENDING').length;
      return totals;
    },
    { framesDone: 0, activeChunks: 0, pendingChunks: 0 },
  );
}

function isDeletableJob(job) {
  return ['CANCELLED', 'COMPLETED', 'FAILED'].includes(job?.status);
}

function getChunkFrames(chunk) {
  const frames = [];
  for (let frame = chunk.startFrame; frame <= chunk.endFrame; frame += 1) {
    frames.push(frame);
  }
  return frames;
}

createRoot(document.getElementById('root')).render(<App />);
