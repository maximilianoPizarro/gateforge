import React, { useState } from 'react';
import {
  Card,
  CardContent,
  CardHeader,
  Typography,
  Button,
  TextField,
  Grid,
  Chip,
  IconButton,
  Snackbar,
  makeStyles,
} from '@material-ui/core';
import { Alert } from '@material-ui/lab';
import AddIcon from '@material-ui/icons/Add';
import DeleteIcon from '@material-ui/icons/Delete';
import SaveIcon from '@material-ui/icons/Save';
import { useEntity } from '@backstage/plugin-catalog-react';
import { fetchApiRef, useApi } from '@backstage/core-plugin-api';
import { EntityForm } from './EntityForm';

const useStyles = makeStyles(theme => ({
  form: {
    display: 'flex',
    flexDirection: 'column',
    gap: theme.spacing(2),
  },
  actions: {
    display: 'flex',
    gap: theme.spacing(2),
    marginTop: theme.spacing(2),
  },
  tagRow: {
    display: 'flex',
    gap: theme.spacing(1),
    flexWrap: 'wrap',
    alignItems: 'center',
  },
}));

export const ComponentEditorTab = () => {
  const classes = useStyles();
  const { entity } = useEntity();
  const fetchApi = useApi(fetchApiRef);
  const [saving, setSaving] = useState(false);
  const [success, setSuccess] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const entityAnnotations = entity.metadata.annotations || {};
  const origin = entityAnnotations['backstage.io/managed-by-origin-location'] || '';
  const isGateforgeManaged =
    origin.startsWith('gateforge:') ||
    entityAnnotations['gateforge.io/managed-by'] === 'gateforge';

  const [description, setDescription] = useState(entity.metadata.description || '');
  const [tags, setTags] = useState<string[]>((entity.metadata.tags as string[]) || []);
  const [newTag, setNewTag] = useState('');

  const annotations = entity.metadata.annotations || {};
  const [editableAnnotations, setEditableAnnotations] = useState<Record<string, string>>(
    Object.fromEntries(
      Object.entries(annotations).filter(([k]) => k.startsWith('kuadrant.io/')),
    ),
  );

  const addTag = () => {
    const t = newTag.trim().toLowerCase().replace(/[^a-z0-9+#-]/g, '-');
    if (t && !tags.includes(t)) {
      setTags([...tags, t]);
      setNewTag('');
    }
  };

  const removeTag = (tag: string) => {
    setTags(tags.filter(t => t !== tag));
  };

  const updateAnnotation = (key: string, value: string) => {
    setEditableAnnotations({ ...editableAnnotations, [key]: value });
  };

  const handleSave = async () => {
    if (!entity.metadata.uid) {
      setError('Entity UID not available');
      return;
    }

    setSaving(true);
    setError(null);

    try {
      const updated = {
        ...entity,
        metadata: {
          ...entity.metadata,
          description,
          tags,
          annotations: {
            ...entity.metadata.annotations,
            ...editableAnnotations,
          },
        },
      };

      const resp = await fetchApi.fetch(`/api/catalog/entities/by-uid/${entity.metadata.uid}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(updated),
      });

      if (!resp.ok) {
        const body = await resp.text();
        throw new Error(`HTTP ${resp.status}: ${body}`);
      }

      setSuccess(true);
    } catch (err: any) {
      setError(err.message || 'Failed to update entity');
    } finally {
      setSaving(false);
    }
  };

  if (!isGateforgeManaged) {
    return (
      <Card>
        <CardContent>
          <Typography color="textSecondary">
            This component is not managed by GateForge. Only GateForge-migrated components
            (without a linked repository) can be edited directly from this view.
          </Typography>
        </CardContent>
      </Card>
    );
  }

  return (
    <>
      <Card>
        <CardHeader
          title="Component Editor — GateForge"
          subheader="Edit annotations, tags, and description for this GateForge-managed component"
        />
        <CardContent>
          <EntityForm
            description={description}
            tags={tags}
            newTag={newTag}
            editableAnnotations={editableAnnotations}
            saving={saving}
            onDescriptionChange={setDescription}
            onTagsChange={setTags}
            onNewTagChange={setNewTag}
            onAddTag={addTag}
            onRemoveTag={removeTag}
            onUpdateAnnotation={updateAnnotation}
            onSave={handleSave}
          />
        </CardContent>
      </Card>

      <Snackbar open={success} autoHideDuration={4000} onClose={() => setSuccess(false)}>
        <Alert severity="success" onClose={() => setSuccess(false)}>
          Component updated successfully
        </Alert>
      </Snackbar>

      <Snackbar open={!!error} autoHideDuration={6000} onClose={() => setError(null)}>
        <Alert severity="error" onClose={() => setError(null)}>
          {error}
        </Alert>
      </Snackbar>
    </>
  );
};
