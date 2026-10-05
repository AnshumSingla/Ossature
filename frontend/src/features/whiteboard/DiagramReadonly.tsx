import { useMemo } from 'react';
import ReactFlow, { Background, BackgroundVariant, Controls, type Edge, type Node } from 'reactflow';
import 'reactflow/dist/style.css';
import type { Diagram } from '../../types/protocol';
import { ShapeNode, type ShapeKind, type ShapeNodeData } from './nodes/ShapeNode';
import { LabeledEdge, type LabeledEdgeData } from './edges/LabeledEdge';

const nodeTypes = { shape: ShapeNode };
const edgeTypes = { labeled: LabeledEdge };
const noop = () => {};

/** Static, non-interactive render of a saved diagram - session history/replay (PRD Phase 4). */
export function DiagramReadonly({ diagram }: { diagram: Diagram }) {
  const nodes: Node<ShapeNodeData>[] = useMemo(
    () =>
      diagram.nodes.map((n) => ({
        id: n.id,
        position: { x: n.x, y: n.y },
        type: 'shape',
        data: {
          label: n.label,
          shape: (n.shape as ShapeKind) ?? 'component',
          locked: true,
          onRename: noop,
        },
      })),
    [diagram.nodes],
  );

  const edges: Edge<LabeledEdgeData>[] = useMemo(
    () =>
      diagram.edges.map((e) => ({
        id: e.id,
        source: e.source,
        target: e.target,
        label: e.label ?? '',
        type: 'labeled',
        data: { locked: true, onRename: noop },
      })),
    [diagram.edges],
  );

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      edgeTypes={edgeTypes}
      nodesDraggable={false}
      nodesConnectable={false}
      elementsSelectable={false}
      fitView
    >
      <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
      <Controls showInteractive={false} />
    </ReactFlow>
  );
}
