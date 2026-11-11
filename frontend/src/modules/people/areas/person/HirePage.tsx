import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  DatePicker,
  Divider,
  Form,
  Input,
  InputNumber,
  List,
  Radio,
  Result,
  Select,
  Space,
  Steps,
  Tag,
  Typography,
} from 'antd';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { Link, useNavigate } from '@tanstack/react-router';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { FormDrawer, MoneyInput, PageHeader, ProblemAlert, useFeedback } from '@/shared/components';
import { DATE_FORMAT } from '@/shared/format';
import {
  applyFieldErrors,
  blankToNull,
  CodeValueSelect,
  CostCentreSelect,
  CountrySelect,
  EmployeeCategorySelect,
  EmployeeSelect,
  GradeSelect,
  JobTitleSelect,
  LegalEntitySelect,
  LocationSelect,
  OrgUnitSelect,
  toIsoDate,
} from '../../common';
import {
  CodeLists,
  CONTRACT_TYPES,
  CONTRACT_TYPES_WITH_END,
  NATIONAL_ID_TYPES,
  PAY_BASES,
  SALARY_KINDS,
  WORKER_CATEGORIES,
  type ContractTypeCode,
  type HireMatchView,
  type HireOption,
  type HireRequest,
  type HireResult,
  type HireType,
  type NationalIdType,
} from './api';
import { useCancelHire, useHire, useHireMatch } from './queries';
import { nationalIdRule, normaliseId, PATTERNS } from './shared';
import './i18n';

/** All wizard fields live in one form (kept across steps). Dates are Dayjs until submit. */
interface HireValues {
  nationalIdType?: NationalIdType;
  nationalId?: string;
  nationalityCode?: string;
  passportNo?: string;
  taxCode?: string;
  siNumber?: string;
  hiCardNo?: string;
  legalEntityId: UUID;
  hireDate: Dayjs;
  familyName?: string;
  middleName?: string;
  givenName?: string;
  latinName?: string;
  preferredName?: string;
  birthDate?: Dayjs | null;
  genderCode?: string;
  originalHireDate?: Dayjs | null;
  seniorityDate?: Dayjs | null;
  probationEndDate?: Dayjs | null;
  workerCategoryCode: string;
  employeeCategoryCode?: string;
  workingRegimeCode?: string;
  assignment: {
    orgUnitId: UUID;
    jobTitleId?: UUID;
    gradeId?: UUID;
    locationId?: UUID;
    managerEmploymentId?: UUID;
    costCentreId?: UUID;
    fte?: number | null;
  };
  contract: {
    contractTypeCode: ContractTypeCode;
    startDate?: Dayjs | null;
    endDate?: Dayjs | null;
    probationDays?: number | null;
    probationPayPct?: number | null;
    signatoryName?: string;
    signatoryTitle?: string;
  };
  salary: {
    payBasis?: 'GROSS' | 'NET';
    salaryKind?: (typeof SALARY_KINDS)[number];
    baseSalaryAmount: number;
    agreedNetAmount?: number | null;
    siSalaryAmount?: number | null;
    probationPct?: number | null;
    allowances?: { componentCode: string; amount: number; inSiSalary?: boolean }[];
  };
}

/** Fields validated before leaving each step (name paths of `HireValues`). */
const STEP_FIELDS: (string | string[])[][] = [
  ['nationalIdType', 'nationalId', 'nationalityCode', 'passportNo', 'taxCode', 'siNumber', 'legalEntityId', 'hireDate'],
  ['familyName', 'givenName', 'middleName', 'latinName', 'preferredName', 'birthDate', 'genderCode', 'hiCardNo'],
  [
    'workerCategoryCode',
    'employeeCategoryCode',
    'originalHireDate',
    'seniorityDate',
    'probationEndDate',
    ['assignment', 'orgUnitId'],
    ['assignment', 'fte'],
  ],
  [['contract', 'contractTypeCode'], ['contract', 'endDate'], ['salary', 'baseSalaryAmount']],
];

/** Existing person chosen from the blocking matches, with the hire type the backend offered. */
interface Chosen {
  personId: UUID;
  fullName: string;
  hireType: HireType;
}

/**
 * Direct-hire wizard (US-PPL-018/020/021): duplicate check → person → employment + first assignment → contract +
 * salary → review. Submits one `POST /people/hires` (approved through `hr.HIRE`); a pre-hire can be cancelled.
 */
export function HirePage() {
  const { t } = useTranslation('people-person');
  const { t: tp } = useTranslation('people');
  const [form] = Form.useForm<HireValues>();
  const [step, setStep] = useState(0);
  const [match, setMatch] = useState<HireMatchView | null>(null);
  const [chosen, setChosen] = useState<Chosen | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [result, setResult] = useState<HireResult | null>(null);
  const matchMutation = useHireMatch();
  const hire = useHire();

  const idType = Form.useWatch('nationalIdType', form);
  const legalEntityId = Form.useWatch('legalEntityId', form);
  const contractType = Form.useWatch(['contract', 'contractTypeCode'], form) as ContractTypeCode | undefined;
  const payBasis = Form.useWatch(['salary', 'payBasis'], form);

  const reset = () => {
    form.resetFields();
    setStep(0);
    setMatch(null);
    setChosen(null);
    setAcknowledged(false);
    setResult(null);
    hire.reset();
    matchMutation.reset();
  };

  const runMatch = async () => {
    let v: HireValues;
    try {
      v = (await form.validateFields(STEP_FIELDS[0] as never)) as HireValues;
    } catch {
      return;
    }
    setChosen(null);
    setAcknowledged(false);
    try {
      const m = await matchMutation.mutateAsync(
        blankToNull({
          nationalIdType: v.nationalIdType,
          nationalId: v.nationalId,
          nationalityCode: v.nationalityCode,
          passportNo: v.passportNo,
          taxCode: v.taxCode,
          siNumber: v.siNumber,
          legalEntityId: v.legalEntityId,
          hireDate: toIsoDate(v.hireDate),
        }),
      );
      setMatch(m);
    } catch {
      setMatch(null);
    }
  };

  const blocked = (match?.blocking.length ?? 0) > 0;
  const canLeaveIdentify =
    match !== null && (blocked ? chosen !== null : match.warnings.length === 0 || acknowledged);
  /** The person step is skipped for an existing person. */
  const visibleSteps = chosen ? [0, 2, 3, 4] : [0, 1, 2, 3, 4];
  const pos = visibleSteps.indexOf(step);

  const next = async () => {
    if (step === 0 && !canLeaveIdentify) return;
    if (step < 4) {
      try {
        await form.validateFields(STEP_FIELDS[step] as never);
      } catch {
        return;
      }
    }
    setStep(visibleSteps[pos + 1] ?? step);
  };

  const submit = async () => {
    const v = form.getFieldsValue(true) as HireValues;
    const body: HireRequest = {
      personId: chosen?.personId ?? null,
      person: chosen
        ? null
        : {
            ...(blankToNull({
              familyName: v.familyName?.trim(),
              middleName: v.middleName,
              givenName: v.givenName?.trim(),
              latinName: v.latinName,
              preferredName: v.preferredName,
              genderCode: v.genderCode,
              nationalityCode: v.nationalityCode,
              nationalIdType: v.nationalIdType ?? (v.passportNo ? 'PASSPORT' : null),
              nationalId: v.nationalId || v.passportNo,
              taxCode: v.taxCode,
              siNumber: v.siNumber,
              hiCardNo: v.hiCardNo,
            }) as { familyName: string; givenName: string }),
            birthDate: toIsoDate(v.birthDate),
            acknowledgeWarnings: acknowledged,
          },
      hireType: chosen?.hireType ?? null,
      legalEntityId: v.legalEntityId,
      hireDate: toIsoDate(v.hireDate) as string,
      originalHireDate: toIsoDate(v.originalHireDate),
      seniorityDate: toIsoDate(v.seniorityDate),
      probationEndDate: toIsoDate(v.probationEndDate),
      workerCategoryCode: v.workerCategoryCode,
      employeeCategoryCode: v.employeeCategoryCode || null,
      workingRegimeCode: v.workingRegimeCode || null,
      assignment: {
        orgUnitId: v.assignment.orgUnitId,
        jobTitleId: v.assignment.jobTitleId ?? null,
        gradeId: v.assignment.gradeId ?? null,
        locationId: v.assignment.locationId ?? null,
        managerEmploymentId: v.assignment.managerEmploymentId ?? null,
        costCentreId: v.assignment.costCentreId ?? null,
        fte: v.assignment.fte ?? null,
      },
      contract: {
        contractTypeCode: v.contract.contractTypeCode,
        startDate: toIsoDate(v.contract.startDate),
        endDate: toIsoDate(v.contract.endDate),
        probationDays: v.contract.probationDays ?? null,
        probationPayPct: v.contract.probationPayPct ?? null,
        signatoryName: v.contract.signatoryName || null,
        signatoryTitle: v.contract.signatoryTitle || null,
        jobTitleId: v.assignment.jobTitleId ?? null,
      },
      salary: {
        payBasis: v.salary.payBasis ?? 'GROSS',
        salaryKind: v.salary.salaryKind ?? 'MONTHLY',
        baseSalaryAmount: v.salary.baseSalaryAmount,
        agreedNetAmount: v.salary.payBasis === 'NET' ? (v.salary.agreedNetAmount ?? null) : null,
        siSalaryAmount: v.salary.siSalaryAmount ?? null,
        probationPct: v.salary.probationPct ?? null,
        currencyCode: 'VND',
        allowances: (v.salary.allowances ?? []).map((a) => ({ ...a, inSiSalary: !!a.inSiSalary })),
      },
      signatoryName: v.contract.signatoryName || null,
      signatoryTitle: v.contract.signatoryTitle || null,
    };
    try {
      setResult(await hire.mutateAsync(body));
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  if (result) return <HireDone result={result} onAnother={reset} />;

  const stepItems = visibleSteps.map((s) => ({
    title: t(`hire.steps.${['identify', 'person', 'employment', 'contract', 'review'][s]}`),
  }));

  return (
    <div>
      <PageHeader title={t('hire.title')} breadcrumb={[{ label: tp('directory.title'), to: '/people/employees' }, { label: t('hire.title') }]} />
      <Steps current={pos} items={stepItems} style={{ marginBottom: 16 }} />
      <Card>
        <Form<HireValues>
          form={form}
          layout="vertical"
          preserve
          initialValues={{ nationalIdType: 'CCCD', nationalityCode: 'VN', workerCategoryCode: 'EMPLOYEE', salary: { payBasis: 'GROSS', salaryKind: 'MONTHLY', allowances: [] } }}
          style={{ maxWidth: 880 }}
        >
          {/* ---------------------------------------------------------------- 0: identify */}
          <div hidden={step !== 0}>
            <Typography.Paragraph type="secondary">{t('hire.identifyHint')}</Typography.Paragraph>
            <Space wrap align="start">
              <Form.Item name="legalEntityId" label={tp('common.legalEntity')} rules={[{ required: true, message: t('hire.legalEntityRequired') }]}>
                <LegalEntitySelect style={{ width: 280 }} onChange={() => setMatch(null)} />
              </Form.Item>
              <Form.Item name="hireDate" label={t('hire.hireDate')} rules={[{ required: true, message: t('hire.hireDateRequired') }]}>
                <DatePicker format={DATE_FORMAT} onChange={() => setMatch(null)} />
              </Form.Item>
            </Space>
            <Space wrap align="start">
              <Form.Item name="nationalIdType" label={t('person.nationalIdType')}>
                <Select style={{ width: 200 }} allowClear options={NATIONAL_ID_TYPES.map((v) => ({ value: v, label: t(`person.idTypes.${v}`) }))} />
              </Form.Item>
              <Form.Item name="nationalId" label={t('person.nationalId')} normalize={normaliseId} rules={[nationalIdRule(idType, t)]}>
                <Input style={{ width: 220 }} autoComplete="off" onChange={() => setMatch(null)} />
              </Form.Item>
              <Form.Item name="nationalityCode" label={t('person.nationality')}>
                <CountrySelect nationality style={{ width: 220 }} />
              </Form.Item>
            </Space>
            <Space wrap align="start">
              <Form.Item name="passportNo" label={t('hire.passportNo')} normalize={normaliseId} rules={[{ pattern: PATTERNS.PASSPORT, message: t('person.passportInvalid') }]}>
                <Input style={{ width: 200 }} autoComplete="off" onChange={() => setMatch(null)} />
              </Form.Item>
              <Form.Item name="taxCode" label={t('person.taxCode')} normalize={normaliseId} rules={[{ pattern: PATTERNS.TAX_CODE, message: t('person.taxCodeInvalid') }]}>
                <Input style={{ width: 200 }} autoComplete="off" onChange={() => setMatch(null)} />
              </Form.Item>
              <Form.Item name="siNumber" label={t('person.siNumber')} normalize={normaliseId} rules={[{ pattern: PATTERNS.SI_NUMBER, message: t('person.siNumberInvalid') }]}>
                <Input style={{ width: 200 }} autoComplete="off" onChange={() => setMatch(null)} />
              </Form.Item>
            </Space>
            <div>
              <Button onClick={() => void runMatch()} loading={matchMutation.isPending}>
                {t('hire.check')}
              </Button>
            </div>
            {matchMutation.error ? <ProblemAlert error={matchMutation.error} style={{ marginTop: 12 }} /> : null}
            {match ? (
              <MatchPanel
                match={match}
                chosen={chosen}
                onChoose={setChosen}
                acknowledged={acknowledged}
                onAcknowledge={setAcknowledged}
              />
            ) : null}
          </div>

          {/* ---------------------------------------------------------------- 1: person */}
          <div hidden={step !== 1}>
            <Space wrap align="start">
              <Form.Item name="familyName" label={t('person.familyName')} rules={[{ required: !chosen, whitespace: true, message: t('person.familyNameRequired') }, { max: 100 }]}>
                <Input style={{ width: 200 }} />
              </Form.Item>
              <Form.Item name="middleName" label={t('person.middleName')} rules={[{ max: 100 }]}>
                <Input style={{ width: 200 }} />
              </Form.Item>
              <Form.Item name="givenName" label={t('person.givenName')} rules={[{ required: !chosen, whitespace: true, message: t('person.givenNameRequired') }, { max: 100 }]}>
                <Input style={{ width: 200 }} />
              </Form.Item>
            </Space>
            <Space wrap align="start">
              <Form.Item name="latinName" label={t('person.latinName')} rules={[{ max: 200 }]}>
                <Input style={{ width: 260 }} />
              </Form.Item>
              <Form.Item name="preferredName" label={t('person.preferredName')} rules={[{ max: 100 }]}>
                <Input style={{ width: 200 }} />
              </Form.Item>
            </Space>
            <Space wrap align="start">
              <Form.Item name="birthDate" label={t('person.birthDate')}>
                <DatePicker format={DATE_FORMAT} />
              </Form.Item>
              <Form.Item name="genderCode" label={t('person.gender')}>
                <CodeValueSelect list={CodeLists.GENDER} style={{ width: 180 }} />
              </Form.Item>
              <Form.Item name="hiCardNo" label={t('person.hiCardNo')} normalize={normaliseId} rules={[{ pattern: PATTERNS.HI_CARD, message: t('person.hiCardInvalid') }]}>
                <Input style={{ width: 220 }} autoComplete="off" />
              </Form.Item>
            </Space>
          </div>

          {/* ---------------------------------------------------------------- 2: employment */}
          <div hidden={step !== 2}>
            <Typography.Title level={5}>{t('hire.employment')}</Typography.Title>
            <Space wrap align="start">
              <Form.Item name="workerCategoryCode" label={t('hire.workerCategory')} rules={[{ required: true, message: t('hire.workerCategoryRequired') }]}>
                <Select style={{ width: 220 }} options={WORKER_CATEGORIES.map((v) => ({ value: v, label: t(`hire.workerCategories.${v}`) }))} />
              </Form.Item>
              <Form.Item name="employeeCategoryCode" label={tp('common.employeeCategory')}>
                <EmployeeCategorySelect style={{ width: 240 }} />
              </Form.Item>
              <Form.Item name="workingRegimeCode" label={t('hire.workingRegime')} rules={[{ max: 64 }]}>
                <Input style={{ width: 180 }} />
              </Form.Item>
            </Space>
            <Space wrap align="start">
              <Form.Item name="originalHireDate" label={t('hire.originalHireDate')}>
                <DatePicker format={DATE_FORMAT} />
              </Form.Item>
              <Form.Item name="seniorityDate" label={t('hire.seniorityDate')}>
                <DatePicker format={DATE_FORMAT} />
              </Form.Item>
              <Form.Item name="probationEndDate" label={t('hire.probationEndDate')}>
                <DatePicker format={DATE_FORMAT} />
              </Form.Item>
            </Space>
            <Typography.Title level={5}>{t('hire.assignment')}</Typography.Title>
            <Space wrap align="start">
              <Form.Item name={['assignment', 'orgUnitId']} label={tp('common.orgUnit')} rules={[{ required: true, message: t('hire.orgUnitRequired') }]}>
                <OrgUnitSelect legalEntityId={legalEntityId} style={{ width: 300 }} />
              </Form.Item>
              <Form.Item name={['assignment', 'jobTitleId']} label={tp('common.jobTitle')}>
                <JobTitleSelect style={{ width: 280 }} />
              </Form.Item>
              <Form.Item name={['assignment', 'gradeId']} label={tp('common.grade')}>
                <GradeSelect style={{ width: 220 }} />
              </Form.Item>
            </Space>
            <Space wrap align="start">
              <Form.Item name={['assignment', 'locationId']} label={tp('common.location')}>
                <LocationSelect legalEntityId={legalEntityId} style={{ width: 280 }} />
              </Form.Item>
              <Form.Item name={['assignment', 'costCentreId']} label={t('hire.costCentre')}>
                <CostCentreSelect legalEntityId={legalEntityId} style={{ width: 240 }} />
              </Form.Item>
              <Form.Item name={['assignment', 'managerEmploymentId']} label={tp('common.manager')}>
                <EmployeeSelect style={{ width: 280 }} />
              </Form.Item>
              <Form.Item name={['assignment', 'fte']} label={tp('common.fte')}>
                <InputNumber min={0.01} max={1} step={0.1} style={{ width: 120 }} />
              </Form.Item>
            </Space>
          </div>

          {/* ---------------------------------------------------------------- 3: contract + salary */}
          <div hidden={step !== 3}>
            <Typography.Title level={5}>{t('hire.contract')}</Typography.Title>
            <Space wrap align="start">
              <Form.Item name={['contract', 'contractTypeCode']} label={t('hire.contractType')} rules={[{ required: true, message: t('hire.contractTypeRequired') }]}>
                <Select style={{ width: 240 }} options={CONTRACT_TYPES.map((v) => ({ value: v, label: t(`hire.contractTypes.${v}`) }))} />
              </Form.Item>
              <Form.Item name={['contract', 'startDate']} label={t('hire.startDate')} extra={t('hire.startDateHint')}>
                <DatePicker format={DATE_FORMAT} />
              </Form.Item>
              <Form.Item
                name={['contract', 'endDate']}
                label={t('hire.endDate')}
                rules={[{ required: !!contractType && CONTRACT_TYPES_WITH_END.includes(contractType), message: t('hire.endDateRequired') }]}
              >
                <DatePicker format={DATE_FORMAT} />
              </Form.Item>
            </Space>
            <Space wrap align="start">
              <Form.Item name={['contract', 'probationDays']} label={t('hire.probationDays')}>
                <InputNumber min={0} max={180} precision={0} style={{ width: 140 }} />
              </Form.Item>
              <Form.Item name={['contract', 'probationPayPct']} label={t('hire.probationPayPct')}>
                <InputNumber min={0} max={100} style={{ width: 140 }} />
              </Form.Item>
              <Form.Item name={['contract', 'signatoryName']} label={t('hire.signatoryName')} rules={[{ max: 200 }]}>
                <Input style={{ width: 220 }} />
              </Form.Item>
              <Form.Item name={['contract', 'signatoryTitle']} label={t('hire.signatoryTitle')} rules={[{ max: 200 }]}>
                <Input style={{ width: 200 }} />
              </Form.Item>
            </Space>
            <Divider />
            <Typography.Title level={5}>{t('hire.salary')}</Typography.Title>
            <Space wrap align="start">
              <Form.Item name={['salary', 'payBasis']} label={t('hire.payBasis')}>
                <Radio.Group optionType="button" options={PAY_BASES.map((b) => ({ value: b, label: b }))} />
              </Form.Item>
              <Form.Item name={['salary', 'salaryKind']} label={t('hire.salaryKind')}>
                <Select style={{ width: 160 }} options={SALARY_KINDS.map((k) => ({ value: k, label: t(`hire.salaryKinds.${k}`) }))} />
              </Form.Item>
            </Space>
            <Space wrap align="start">
              <Form.Item name={['salary', 'baseSalaryAmount']} label={t('hire.baseSalary')} rules={[{ required: true, message: t('hire.baseSalaryRequired') }]}>
                <MoneyInput style={{ width: 220 }} min={1} />
              </Form.Item>
              {payBasis === 'NET' ? (
                <Form.Item name={['salary', 'agreedNetAmount']} label={t('hire.agreedNet')}>
                  <MoneyInput style={{ width: 220 }} />
                </Form.Item>
              ) : null}
              <Form.Item name={['salary', 'siSalaryAmount']} label={t('hire.siSalary')}>
                <MoneyInput style={{ width: 220 }} />
              </Form.Item>
              <Form.Item name={['salary', 'probationPct']} label={t('hire.probationPct')}>
                <InputNumber min={0} max={100} style={{ width: 140 }} />
              </Form.Item>
            </Space>
            <Typography.Text strong>{t('hire.allowances')}</Typography.Text>
            <Form.List name={['salary', 'allowances']}>
              {(fields, { add, remove }) => (
                <>
                  {fields.map((f) => (
                    <Space key={f.key} align="baseline" wrap>
                      <Form.Item name={[f.name, 'componentCode']} rules={[{ required: true, whitespace: true, message: t('hire.componentCodeRequired') }, { max: 64 }]}>
                        <Input placeholder={t('hire.componentCode')} aria-label={t('hire.componentCode')} style={{ width: 200 }} />
                      </Form.Item>
                      <Form.Item name={[f.name, 'amount']} rules={[{ required: true, message: t('hire.amount') }]}>
                        <MoneyInput placeholder={t('hire.amount')} aria-label={t('hire.amount')} style={{ width: 200 }} />
                      </Form.Item>
                      <Form.Item name={[f.name, 'inSiSalary']} valuePropName="checked">
                        <Checkbox>{t('hire.inSiSalary')}</Checkbox>
                      </Form.Item>
                      <MinusCircleOutlined onClick={() => remove(f.name)} />
                    </Space>
                  ))}
                  <div>
                    <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ inSiSalary: false })}>
                      {t('hire.addAllowance')}
                    </Button>
                  </div>
                </>
              )}
            </Form.List>
          </div>

          {/* ---------------------------------------------------------------- 4: review */}
          {step === 4 ? <HireReview values={form.getFieldsValue(true) as HireValues} chosen={chosen} /> : null}
        </Form>

        {hire.error ? <ProblemAlert error={hire.error} style={{ marginTop: 12 }} /> : null}

        <Space style={{ marginTop: 16 }}>
          {pos > 0 ? <Button onClick={() => setStep(visibleSteps[pos - 1] ?? 0)}>{t('hire.back')}</Button> : null}
          {step < 4 ? (
            <Button type="primary" disabled={step === 0 && !canLeaveIdentify} onClick={() => void next()}>
              {t('hire.next')}
            </Button>
          ) : (
            <Button type="primary" loading={hire.isPending} onClick={() => void submit()}>
              {t('hire.submit')}
            </Button>
          )}
        </Space>
      </Card>
    </div>
  );
}

/** Result of the duplicate check: blocking matches (choose the person + hire type) and tax/SI warnings. */
function MatchPanel({
  match,
  chosen,
  onChoose,
  acknowledged,
  onAcknowledge,
}: {
  match: HireMatchView;
  chosen: Chosen | null;
  onChoose: (c: Chosen | null) => void;
  acknowledged: boolean;
  onAcknowledge: (v: boolean) => void;
}) {
  const { t } = useTranslation('people-person');
  if (match.blocking.length === 0 && match.warnings.length === 0) {
    return <Alert type="success" showIcon message={t('hire.noMatch')} style={{ marginTop: 12 }} />;
  }
  return (
    <div style={{ marginTop: 12 }}>
      {match.blocking.length > 0 ? (
        <Alert
          type="warning"
          showIcon
          message={t('hire.blockingTitle')}
          description={
            <>
              <Typography.Paragraph>{t('hire.blockingHint')}</Typography.Paragraph>
              <List
                size="small"
                dataSource={match.blocking}
                renderItem={(o) => {
                  const hireOptions = o.options.filter((x): x is HireType => x !== 'ASSIGNMENT_CHANGE');
                  const onlyChange = hireOptions.length === 0 && o.options.includes('ASSIGNMENT_CHANGE');
                  const active = o.employments.find((e) => e.status === 'ACTIVE' || e.status === 'PRE_HIRE');
                  return (
                    <List.Item
                      actions={
                        onlyChange
                          ? active
                            ? [
                                <Link key="open" to="/people/employees/$employmentId" params={{ employmentId: active.employmentId }}>
                                  {t('hire.openProfile')}
                                </Link>,
                              ]
                            : []
                          : hireOptions.map((opt: HireOption) => (
                              <Button
                                key={opt}
                                size="small"
                                type={chosen?.personId === o.candidate.personId && chosen.hireType === opt ? 'primary' : 'default'}
                                onClick={() => onChoose({ personId: o.candidate.personId, fullName: o.candidate.fullName, hireType: opt as HireType })}
                              >
                                {t(`hire.options.${opt}`)}
                              </Button>
                            ))
                      }
                    >
                      <Space direction="vertical" size={0}>
                        <Typography.Text strong>
                          {o.candidate.fullName}
                          {o.candidate.birthYear ? ` · ${t('hire.birthYear', { year: o.candidate.birthYear })}` : ''}
                        </Typography.Text>
                        <Typography.Text type="secondary">
                          {t('hire.matchedOn', { field: o.candidate.matchedOn })}
                          {o.employments.length > 0 ? ` · ${o.employments.map((e) => `${e.employeeNo} (${e.status})`).join(', ')}` : ''}
                        </Typography.Text>
                        {onlyChange ? <Typography.Text type="danger">{t('hire.assignmentChangeOnly')}</Typography.Text> : null}
                      </Space>
                    </List.Item>
                  );
                }}
              />
            </>
          }
        />
      ) : null}
      {match.blocking.length === 0 && match.warnings.length > 0 ? (
        <Alert
          type="info"
          showIcon
          message={t('hire.warningsTitle')}
          description={
            <>
              <Typography.Paragraph>{t('hire.warningsHint')}</Typography.Paragraph>
              <List
                size="small"
                dataSource={match.warnings}
                renderItem={(c) => (
                  <List.Item>
                    {c.fullName}
                    {c.birthYear ? ` · ${t('hire.birthYear', { year: c.birthYear })}` : ''} · {t('hire.matchedOn', { field: c.matchedOn })}
                    {c.employeeNos.length > 0 ? ` · ${c.employeeNos.join(', ')}` : ''}
                  </List.Item>
                )}
              />
              <Checkbox checked={acknowledged} onChange={(e) => onAcknowledge(e.target.checked)}>
                {t('hire.acknowledgeWarnings')}
              </Checkbox>
            </>
          }
        />
      ) : null}
      {chosen ? <Alert type="success" showIcon style={{ marginTop: 8 }} message={t('hire.selectedExisting', { name: chosen.fullName })} /> : null}
    </div>
  );
}

function HireReview({ values: v, chosen }: { values: HireValues; chosen: Chosen | null }) {
  const { t } = useTranslation('people-person');
  const name = chosen ? chosen.fullName : [v.familyName, v.middleName, v.givenName].filter(Boolean).join(' ');
  return (
    <div>
      <Alert type="info" showIcon message={t('hire.reviewHint')} style={{ marginBottom: 12 }} />
      <Space direction="vertical">
        <Typography.Text strong>{name}</Typography.Text>
        {chosen ? <Tag color="blue">{t(`hire.options.${chosen.hireType}`)}</Tag> : <Tag>{t('hire.options.NEW')}</Tag>}
        <Typography.Text>
          {t('hire.hireDate')}: {v.hireDate?.format(DATE_FORMAT)} · {t('hire.contractType')}:{' '}
          {v.contract?.contractTypeCode ? t(`hire.contractTypes.${v.contract.contractTypeCode}`) : '—'}
        </Typography.Text>
        <Typography.Text>
          {t('hire.baseSalary')}: {v.salary?.baseSalaryAmount?.toLocaleString('vi-VN')} VND ({v.salary?.payBasis ?? 'GROSS'})
        </Typography.Text>
      </Space>
    </div>
  );
}

/** After a successful submission: summary, warnings / missing data, link to the profile, cancel (no-show). */
function HireDone({ result, onAnother }: { result: HireResult; onAnother: () => void }) {
  const { t } = useTranslation('people-person');
  const navigate = useNavigate();
  const [cancelling, setCancelling] = useState(false);
  const [cancelled, setCancelled] = useState(false);
  return (
    <div>
      <Result
        status={cancelled ? 'info' : 'success'}
        title={cancelled ? t('hire.cancelled') : t('hire.doneTitle', { employeeNo: result.employeeNo })}
        subTitle={t('hire.doneSubtitle', { status: result.status, decisionNo: result.decisionNo ?? '—' })}
        extra={[
          <Button
            key="profile"
            type="primary"
            onClick={() => void navigate({ to: '/people/employees/$employmentId', params: { employmentId: result.employmentId } })}
          >
            {t('hire.openProfile')}
          </Button>,
          <Button key="another" onClick={onAnother}>
            {t('hire.another')}
          </Button>,
          !cancelled ? (
            <Button key="cancel" danger onClick={() => setCancelling(true)}>
              {t('hire.cancelHire')}
            </Button>
          ) : null,
        ]}
      />
      {result.warnings.length > 0 ? (
        <Alert
          type="warning"
          showIcon
          message={t('hire.warnings')}
          description={result.warnings.map((w, i) => <div key={i}>{w.message ?? w.code}</div>)}
          style={{ marginBottom: 12 }}
        />
      ) : null}
      {result.missingData.length > 0 ? (
        <Alert type="info" showIcon message={t('hire.missingData')} description={result.missingData.join(', ')} />
      ) : null}
      <CancelHireDrawer
        employmentId={cancelling ? result.employmentId : null}
        onClose={() => setCancelling(false)}
        onCancelled={() => {
          setCancelled(true);
          setCancelling(false);
        }}
      />
    </div>
  );
}

/** Cancels a pre-hire before its start date (US-PPL-020, no-show). Reusable from other screens of this area. */
export function CancelHireDrawer({
  employmentId,
  version,
  onClose,
  onCancelled,
}: {
  employmentId: UUID | null;
  version?: number;
  onClose: () => void;
  onCancelled?: () => void;
}) {
  const { t } = useTranslation('people-person');
  const fb = useFeedback();
  const [form] = Form.useForm<{ reasonCode?: string; reason: string }>();
  const cancel = useCancelHire();
  const submit = async () => {
    if (!employmentId) return;
    let v: { reasonCode?: string; reason: string };
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await cancel.mutateAsync({ employmentId, reasonCode: v.reasonCode || null, reason: v.reason.trim(), version: version ?? null });
      fb.done(t('hire.cancelled'));
      onCancelled?.();
    } catch {
      /* shown in the drawer */
    }
  };
  return (
    <FormDrawer open={employmentId !== null} onClose={onClose} title={t('hire.cancelTitle')} onSubmit={() => void submit()} submitting={cancel.isPending} error={cancel.error} width={440}>
      <Form form={form} layout="vertical">
        <Form.Item name="reasonCode" label={t('hire.cancelReasonCode')} rules={[{ max: 64 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="reason" label={t('hire.cancelReason')} rules={[{ required: true, whitespace: true, message: t('hire.cancelReasonRequired') }, { max: 1000 }]}>
          <Input.TextArea rows={3} maxLength={1000} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
