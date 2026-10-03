import json
import math

import pytest

from app.calibration import PlattCalibration, load_calibration, logit, sigmoid


def test_sigmoid_is_stable_for_extreme_values():
    assert sigmoid(1000) == 1.0 and sigmoid(-1000) == 0.0
    assert sigmoid(0) == 0.5 and 0.999 < sigmoid(7) < 1.0


def test_platt_is_monotonic_so_ranking_is_preserved():
    cal = PlattCalibration(a=0.4, b=-3.0)
    margins = [-20, -3, 0, 2, 7.5, 18, 35]
    out = [cal.apply(m) for m in margins]
    assert out == sorted(out) and len(set(out)) == len(out)


def test_margins_that_saturate_float32_softmax_stay_distinct_after_calibration():
    cal = PlattCalibration(a=0.3, b=-4.0)
    assert cal.apply(12.0) != cal.apply(16.0)  # sigmoid(12) i sigmoid(16) w float32 byłyby oba 1.0


def test_target_prior_shifts_intercept_correctly():
    cal = PlattCalibration(a=1.0, b=0.0, train_prior=0.6)
    base = cal.apply(0.0)
    lower = cal.apply(0.0, target_prior=0.05)
    assert base == 0.5 and lower < 0.05  # w ruchu z 5% ataków ten sam margines znaczy znacznie mniej
    assert math.isclose(logit(lower), logit(0.05) - logit(0.6), rel_tol=1e-9)
    assert math.isclose(cal.apply(0.0, target_prior=0.6), 0.5)  # ten sam prior = brak zmiany


def test_target_prior_requires_train_prior_and_valid_range():
    with pytest.raises(ValueError):
        PlattCalibration(a=1, b=0).apply(0.0, target_prior=0.1)
    with pytest.raises(ValueError):
        PlattCalibration(a=1, b=0, train_prior=0.5).apply(0.0, target_prior=1.0)


def test_load_calibration_reads_json_and_rejects_other_methods(tmp_path):
    f = tmp_path / "c.json"
    f.write_text(json.dumps({"method": "platt", "a": 0.5, "b": -1.0, "fit": {"prior": 0.58}}))
    assert load_calibration(f) == PlattCalibration(a=0.5, b=-1.0, train_prior=0.58)
    f.write_text(json.dumps({"method": "isotonic"}))
    with pytest.raises(ValueError):
        load_calibration(f)
