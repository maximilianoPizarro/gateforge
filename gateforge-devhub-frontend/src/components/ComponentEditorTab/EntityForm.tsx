import React from 'react';
import {
  TextField,
  Button,
  Chip,
  IconButton,
  Grid,
  Typography,
  makeStyles,
} from '@material-ui/core';
import AddIcon from '@material-ui/icons/Add';
import DeleteIcon from '@material-ui/icons/Delete';
import SaveIcon from '@material-ui/icons/Save';

interface EntityFormProps {
  description: string;
  tags: string[];
  newTag: string;
  editableAnnotations: Record<string, string>;
  saving: boolean;
  onDescriptionChange: (val: string) => void;
  onTagsChange: (val: string[]) => void;
  onNewTagChange: (val: string) => void;
  onAddTag: () => void;
  onRemoveTag: (tag: string) => void;
  onUpdateAnnotation: (key: string, value: string) => void;
  onSave: () => void;
}

const useStyles = makeStyles(theme => ({
  form: {
    display: 'flex',
    flexDirection: 'column',
    gap: theme.spacing(2),
  },
  tagRow: {
    display: 'flex',
    gap: theme.spacing(1),
    flexWrap: 'wrap',
    alignItems: 'center',
  },
  tagInput: {
    display: 'flex',
    gap: theme.spacing(1),
    alignItems: 'center',
  },
  actions: {
    display: 'flex',
    gap: theme.spacing(2),
    marginTop: theme.spacing(2),
  },
  sectionTitle: {
    marginTop: theme.spacing(2),
    marginBottom: theme.spacing(1),
    fontWeight: 600,
  },
}));

export const EntityForm: React.FC<EntityFormProps> = ({
  description,
  tags,
  newTag,
  editableAnnotations,
  saving,
  onDescriptionChange,
  onNewTagChange,
  onAddTag,
  onRemoveTag,
  onUpdateAnnotation,
  onSave,
}) => {
  const classes = useStyles();

  return (
    <div className={classes.form}>
      <TextField
        label="Description"
        value={description}
        onChange={e => onDescriptionChange(e.target.value)}
        multiline
        rows={3}
        variant="outlined"
        fullWidth
      />

      <div>
        <Typography variant="subtitle2" className={classes.sectionTitle}>Tags</Typography>
        <div className={classes.tagRow}>
          {tags.map(tag => (
            <Chip
              key={tag}
              label={tag}
              size="small"
              onDelete={() => onRemoveTag(tag)}
              color="primary"
              variant="outlined"
            />
          ))}
        </div>
        <div className={classes.tagInput}>
          <TextField
            size="small"
            label="New tag"
            value={newTag}
            onChange={e => onNewTagChange(e.target.value)}
            variant="outlined"
            onKeyDown={e => e.key === 'Enter' && onAddTag()}
          />
          <IconButton size="small" onClick={onAddTag} color="primary">
            <AddIcon />
          </IconButton>
        </div>
      </div>

      <div>
        <Typography variant="subtitle2" className={classes.sectionTitle}>Kuadrant Annotations</Typography>
        <Grid container spacing={2}>
          {Object.entries(editableAnnotations).map(([key, value]) => (
            <Grid item xs={12} sm={6} key={key}>
              <TextField
                label={key}
                value={value}
                onChange={e => onUpdateAnnotation(key, e.target.value)}
                variant="outlined"
                fullWidth
                size="small"
              />
            </Grid>
          ))}
        </Grid>
      </div>

      <div className={classes.actions}>
        <Button
          variant="contained"
          color="primary"
          startIcon={<SaveIcon />}
          onClick={onSave}
          disabled={saving}
        >
          {saving ? 'Saving…' : 'Save Changes'}
        </Button>
      </div>
    </div>
  );
};
