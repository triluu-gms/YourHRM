import { useState } from 'react';
import { Alert, Button, Card, Form } from 'antd';
import { useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { FormDrawer, ProblemAlert, useFeedback } from '@/shared/components';
import { applyFieldErrors } from '../../common';
import type { SeparationPreview } from './api';
import { AssessmentView, AttributeFields, NS, toAttributes, type AttributeValues } from './parts';
import { useCreateSeparation, usePreviewSeparation, useSeparationReasons } from './queries';

/** Mounted only while open, so every opening starts from a clean state. */
export function StartSeparationDrawer(props: {
  open: boolean;
  onClose: () => void;
  employmentId: UUID;
  legalEntityId?: UUID | null;
}) {
  return props.open ? <StartSeparationDrawerBody {...props} /> : null;
}

/**
 * "Start separation" (US-PPL-060): legal ground + dates, **Preview** (notice, protection, severance; nothing stored),
 * then **Create draft**. Creating opens the separation's detail page.
 */
function StartSeparationDrawerBody({
  open,
  onClose,
  employmentId,
  legalEntityId,
}: {
  open: boolean;
  onClose: () => void;
  employmentId: UUID;
  legalEntityId?: UUID | null;
}) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const navigate = useNavigate();
  const [form] = Form.useForm<AttributeValues>();
  const reasons = useSeparationReasons(legalEntityId, null, open);
  const preview = usePreviewSeparation(employmentId);
  const create = useCreateSeparation(employmentId);
  const [result, setResult] = useState<SeparationPreview | null>(null);


  const values = async () => {
    try {
      return toAttributes(await form.validateFields());
    } catch {
      return null;
    }
  };

  const onPreview = async () => {
    const body = await values();
    if (!body) return;
    try {
      setResult(await preview.mutateAsync(body));
    } catch (e) {
      setResult(null);
      applyFieldErrors(form, e);
    }
  };

  const onCreate = async () => {
    const body = await values();
    if (!body) return;
    try {
      const created = await create.mutateAsync(body);
      fb.done(t('start.created'));
      onClose();
      void navigate({ to: '/people/separations/$separationId', params: { separationId: created.separation.id } });
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={t('start.title')}
      width={640}
      onSubmit={() => void onCreate()}
      submitting={create.isPending}
      submitLabel={t('start.create')}
      error={create.error ?? reasons.error}
      footerExtra={
        <Button loading={preview.isPending} onClick={() => void onPreview()}>
          {t('start.preview')}
        </Button>
      }
    >
      <Form<AttributeValues> form={form} layout="vertical" onValuesChange={() => setResult(null)}>
        <AttributeFields form={form} catalogue={reasons.data} catalogueLoading={reasons.isLoading} />
      </Form>
      {preview.error ? <ProblemAlert error={preview.error} style={{ marginBottom: 12 }} /> : null}
      {result ? (
        <Card size="small" title={t('start.previewTitle')}>
          <AssessmentView
            notice={result.notice}
            protection={result.protection}
            severanceType={result.severanceType}
            severanceEligibility={result.severanceEligibility}
            statutoryCode={result.statutoryCode}
            terminationDate={result.terminationDate}
            accessRevocationDate={result.accessRevocationDate}
            warnings={result.warnings}
          />
        </Card>
      ) : (
        <Alert type="info" showIcon message={t('start.previewFirst')} />
      )}
    </FormDrawer>
  );
}
