import { useCallback } from 'react';
import ReactFlow, { Background, Controls, MiniMap } from 'reactflow';
import 'reactflow/dist/style.css';
import { useWhiteboardStore } from './whiteboardStore';

export function Whiteboard() {
  const { nodes, edges, locked, onNodesChange, onEdgesChange, onConnect, addNode, renameNode } =
    useWhiteboardStore();

  const onNodeDoubleClick = useCallback(
    (_: unknown, node: { id: string; data: { label?: string } }) => {
      if (locked) return;
      const next = window.prompt('Label', String(node.data.label ?? ''));
      if (next != null) renameNode(node.id, next);
    },
    [locked, renameNode],
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ padding: 8, borderBottom: '1px solid #ddd', display: 'flex', gap: 8 }}>
        <button type="button" onClick={() => addNode('Component')} disabled={locked}>
          + Component
        </button>
        <button type="button" onClick={() => addNode('Datastore')} disabled={locked}>
          + Datastore
        </button>
        <button type="button" onClick={() => addNode('Client')} disabled={locked}>
          + Client
        </button>
        {locked && <span style={{ color: '#b00', alignSelf: 'center' }}>Diagram locked</span>}
      </div>
      <div style={{ flex: 1 }}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodeDoubleClick={onNodeDoubleClick}
          nodesDraggable={!locked}
          nodesConnectable={!locked}
          edgesFocusable={!locked}
          fitView
        >
          <Background />
          <Controls />
          <MiniMap />
        </ReactFlow>
      </div>
    </div>
  );
}
