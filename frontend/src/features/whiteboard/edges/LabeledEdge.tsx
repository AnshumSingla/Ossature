import { useEffect, useRef, useState } from 'react';
import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath, type EdgeProps } from 'reactflow';

export interface LabeledEdgeData {
  locked: boolean;
  onRename: (label: string) => void;
}

export function LabeledEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  label,
  data,
  selected,
  markerEnd,
}: EdgeProps<LabeledEdgeData>) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(String(label ?? ''));
  const inputRef = useRef<HTMLInputElement>(null);

  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
    borderRadius: 8,
  });

  useEffect(() => setDraft(String(label ?? '')), [label]);
  useEffect(() => {
    if (editing) inputRef.current?.select();
  }, [editing]);

  const commit = () => {
    setEditing(false);
    data?.onRename(draft.trim());
  };

  return (
    <>
      <BaseEdge id={id} path={edgePath} markerEnd={markerEnd} className={selected ? 'edge-selected' : ''} />
      <EdgeLabelRenderer>
        <div
          className="edge-label"
          style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
        >
          {editing ? (
            <input
              ref={inputRef}
              className="edge-label-input"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onBlur={commit}
              onKeyDown={(e) => {
                if (e.key === 'Enter') commit();
                if (e.key === 'Escape') setEditing(false);
              }}
              autoFocus
            />
          ) : (
            (label || !data?.locked) && (
              <span
                className="edge-label-text"
                onDoubleClick={() => !data?.locked && setEditing(true)}
              >
                {label || (!data?.locked ? '+ label' : '')}
              </span>
            )
          )}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}
