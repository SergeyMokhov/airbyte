#
# Copyright (c) 2022 Airbyte, Inc., all rights reserved.
#

from typing import Any, Mapping

from airbyte_cdk.sources.declarative.interpolation.jinja import JinjaInterpolation
from airbyte_cdk.sources.declarative.types import Config


class InterpolatedMapping:
    """Wrapper around a Mapping[str, str] where both the keys and values are to be interpolated."""

    def __init__(self, mapping: Mapping[str, Any], runtime_parameters: Mapping[str, Any]):
        """
        :param mapping: Mapping[str, str] to be evaluated
        :param runtime_parameters: Additional runtime parameters to be used for string interpolation
        """
        self._mapping = mapping
        self._options = runtime_parameters
        self._interpolation = JinjaInterpolation()

    def eval(self, config: Config, **kwargs):
        """
        Wrapper around a Mapping[str, str] that allows for both keys and values to be interpolated.

        :param config: The user-provided configuration as specified by the source's spec
        :param kwargs: Optional parameters used for interpolation
        :return: The interpolated string
        """
        interpolated_values = {
            self._interpolation.eval(name, config, options=self._options, **kwargs): self._eval(value, config, **kwargs)
            for name, value in self._mapping.items()
        }
        return interpolated_values

    def _eval(self, value, config, **kwargs):
        # The values in self._mapping can be of Any type
        # We only want to interpolate them if they are strings
        if type(value) == str:
            return self._interpolation.eval(value, config, options=self._options, **kwargs)
        else:
            return value
